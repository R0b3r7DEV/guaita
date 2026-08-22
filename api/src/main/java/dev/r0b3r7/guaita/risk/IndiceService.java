package dev.r0b3r7.guaita.risk;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cálculo y persistencia del índice de peligro compuesto del DÍA MÁS RECIENTE para los 135
 * municipios (docs/04 §4). No hace backfill histórico (eso es el paso 10). PostGIS da las áreas y
 * los percentiles; {@link Indice} y {@link Regeneracion} combinan. Idempotente (upsert por
 * (ine_code, fecha)); refresca {@code mv_indice_hoy} al final.
 */
@Service
public class IndiceService {

  // Municipios tocados por algún incendio -> última fecha de un incendio que cumple el reparto
  // (perímetro cubre >= umbral de la superficie forestal del término). Solo se calcula la forestal
  // de los términos tocados; el resto no la necesita (f_tiempo neutro).
  private static final String Q_ULTIMO_FUEGO =
      """
      with tocados as (
        select distinct m.ine_code, m.geom mg
        from municipio m
        join incendio_historico i on i.geom && m.geom and st_intersects(i.geom, m.geom)
      ),
      mf as (
        select t.ine_code, t.mg,
               coalesce(sum(st_area(st_intersection(f.geom, t.mg))), 0) fa
        from tocados t
        left join terreno_forestal f on f.geom && t.mg and st_intersects(f.geom, t.mg)
        group by t.ine_code, t.mg
      ),
      fm as (
        select mf.ine_code, i.fecha_inicio,
               coalesce(
                 sum(st_area(st_intersection(st_intersection(i.geom, mf.mg), f.geom))), 0) burned
        from incendio_historico i
        join mf on i.geom && mf.mg and st_intersects(i.geom, mf.mg)
        join terreno_forestal f
          on f.geom && i.geom and st_intersects(f.geom, i.geom) and f.geom && mf.mg
        where i.fecha_inicio <= ?
        group by mf.ine_code, i.fecha_inicio
      )
      select fm.ine_code, max(fm.fecha_inicio) ultimo
      from fm join mf using (ine_code)
      where mf.fa > 0 and fm.burned >= ? * mf.fa
      group by fm.ine_code
      """;

  private static final String Q_FACTORES =
      """
      select e.frac_forestal ff, e.continuidad co, e.peso_modelo pm, e.f_pendiente fp,
             v.comp_vulnerab cv,
             (m.temp_12utc_c >= 30 and m.hr_12utc_pct <= 30 and m.viento_12utc_kmh >= 30) r303030
      from estructural_municipio e
      join vulnerab_municipio v on v.ine_code = e.ine_code
      join meteo_municipio m on m.ine_code = e.ine_code and m.fecha = ?
      where e.ine_code = ?
      """;

  private static final String UPSERT =
      """
      insert into indice_peligro
        (ine_code, fecha, comp_meteo, comp_estructural, comp_vulnerab, indice, nivel,
         alerta_30_30_30, viento_alineado, version_modelo)
      values (?, ?, ?, ?, ?, ?, ?, ?, null, ?)
      on conflict (ine_code, fecha) do update set
        comp_meteo = excluded.comp_meteo, comp_estructural = excluded.comp_estructural,
        comp_vulnerab = excluded.comp_vulnerab, indice = excluded.indice, nivel = excluded.nivel,
        alerta_30_30_30 = excluded.alerta_30_30_30, version_modelo = excluded.version_modelo,
        calculado_en = now()
      """;

  /** Fecha calculada, filas escritas y reparto por nivel (para el log/verificación). */
  public record Resultado(LocalDate fecha, int filas, int[] porNivel) {}

  private final JdbcTemplate jdbc;
  private final ClimatologiaService climatologia;

  public IndiceService(JdbcTemplate jdbc, ClimatologiaService climatologia) {
    this.jdbc = jdbc;
    this.climatologia = climatologia;
  }

  /** Día operativo = FWI más reciente ya convergido (sin calentamiento). */
  public LocalDate diaMasReciente() {
    return jdbc.queryForObject(
        "select max(fecha) from fwi_municipio where not calentamiento", LocalDate.class);
  }

  /**
   * Calcula y persiste el índice de {@code fecha} para los 135. Devuelve el reparto por nivel.
   * f_tiempo aplica la curva de regeneración a los municipios con incendio que cumple el reparto; el
   * resto queda neutro (sin-dato-valor).
   */
  @Transactional
  public Resultado calcularDia(ModeloParams params, LocalDate fecha) {
    String version = params.version();
    ModeloParams.FTiempo cfgT = params.fTiempo();
    Map<String, Double> fTiempo = fTiempoPorMunicipio(fecha, cfgT);

    List<String> munis =
        jdbc.queryForList("select ine_code from municipio order by ine_code", String.class);
    int[] porNivel = new int[5];
    int filas = 0;
    for (String ine : munis) {
      Double compMeteo = climatologia.percentilEvento(ine, fecha, version);
      if (compMeteo == null) {
        throw new IllegalStateException("sin comp_meteo para " + ine + " en " + fecha);
      }
      Map<String, Object> f = jdbc.queryForMap(Q_FACTORES, fecha, ine);
      double parteEstatica =
          Estructural.parteEstatica(
              num(f, "ff"), num(f, "co"), num(f, "pm"), num(f, "fp"));
      double ft = fTiempo.getOrDefault(ine, cfgT.sinDatoValor());
      double compEstructural = Math.min(100.0, Math.max(0.0, parteEstatica * ft));
      double compVulnerab = num(f, "cv");
      boolean r303030 = Boolean.TRUE.equals(f.get("r303030"));

      double indice = Indice.calcular(compMeteo, compEstructural, compVulnerab, params.indice());
      int nivel = Indice.nivel(indice, params.indice().niveles());

      jdbc.update(
          UPSERT, ine, fecha, compMeteo, compEstructural, compVulnerab, indice, nivel, r303030,
          version);
      porNivel[nivel - 1]++;
      filas++;
    }

    jdbc.execute("refresh materialized view mv_indice_hoy");
    return new Resultado(fecha, filas, porNivel);
  }

  /** Calcula el índice del día más reciente. Devuelve el resumen. */
  @Transactional
  public Resultado calcularHoy(ModeloParams params) {
    LocalDate fecha = diaMasReciente();
    if (fecha == null) {
      throw new IllegalStateException("no hay FWI: ejecuta el backfill/ingesta antes del índice");
    }
    return calcularDia(params, fecha);
  }

  private Map<String, Double> fTiempoPorMunicipio(LocalDate fecha, ModeloParams.FTiempo cfg) {
    Map<String, Double> out = new HashMap<>();
    jdbc.query(
        Q_ULTIMO_FUEGO,
        (java.sql.ResultSet rs) -> {
          LocalDate ultimo = rs.getObject("ultimo", LocalDate.class);
          int anios = (int) ChronoUnit.YEARS.between(ultimo, fecha);
          out.put(rs.getString("ine_code"), Regeneracion.fTiempo(anios, cfg));
        },
        fecha,
        cfg.repartoMinFracForestal());
    return out;
  }

  private static double num(Map<String, Object> row, String col) {
    return ((Number) row.get(col)).doubleValue();
  }

  /** Invariantes: 135 filas para la fecha, sin NULL en índice/nivel (los CHECK ya fuerzan rangos). */
  public List<String> asserciones(LocalDate fecha) {
    List<String> fallos = new ArrayList<>();
    Integer provincia = jdbc.queryForObject("select count(*) from municipio", Integer.class);
    Integer filas =
        jdbc.queryForObject(
            "select count(*) from indice_peligro where fecha = ?", Integer.class, fecha);
    if (filas == null || !filas.equals(provincia)) {
      fallos.add(filas + " filas en " + fecha + ", esperaba " + provincia);
    }
    return fallos;
  }
}
