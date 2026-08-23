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

  // --- Backfill histórico (paso 10): sin red, todo de tablas ya cargadas. ---

  // TODAS las fechas de incendio que cumplen el reparto por municipio (sin filtrar por fecha: el
  // filtrado temporal —solo incendios ANTERIORES a cada día— lo hace Java por fila).
  private static final String Q_FUEGOS =
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
        select mf.ine_code, i.fecha_inicio, max(mf.fa) fa,
               coalesce(
                 sum(st_area(st_intersection(st_intersection(i.geom, mf.mg), f.geom))), 0) burned
        from incendio_historico i
        join mf on i.geom && mf.mg and st_intersects(i.geom, mf.mg)
        join terreno_forestal f
          on f.geom && i.geom and st_intersects(f.geom, i.geom) and f.geom && mf.mg
        group by mf.ine_code, i.fecha_inicio
      )
      select ine_code, fecha_inicio from fm where fa > 0 and burned >= ? * fa
      """;

  // Serie diaria por municipio (sin calentamiento): FWI + meteo para la regla 30-30-30.
  private static final String Q_SERIE =
      """
      select f.fecha, extract(doy from f.fecha)::int doy, f.fwi,
             m.temp_12utc_c t, m.hr_12utc_pct h, m.viento_12utc_kmh v
      from fwi_municipio f
      join meteo_municipio m on m.ine_code = f.ine_code and m.fecha = f.fecha
      where f.ine_code = ? and not f.calentamiento
      order by f.fecha
      """;

  private static final String Q_FACT_MUNI =
      """
      select e.frac_forestal ff, e.continuidad co, e.peso_modelo pm, e.f_pendiente fp,
             v.comp_vulnerab cv
      from estructural_municipio e
      join vulnerab_municipio v on v.ine_code = e.ine_code
      where e.ine_code = ?
      """;

  /** Fecha calculada, filas escritas y reparto por nivel (para el log/verificación). */
  public record Resultado(LocalDate fecha, int filas, int[] porNivel) {}

  /** Resumen del backfill histórico. */
  public record BackfillResultado(int municipios, int filas, LocalDate desde, LocalDate hasta) {}

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
   * f_tiempo aplica la curva de regeneración a los municipios con incendio que cumple el reparto;
   * el resto queda neutro (sin-dato-valor).
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
          Estructural.parteEstatica(num(f, "ff"), num(f, "co"), num(f, "pm"), num(f, "fp"));
      double ft = fTiempo.getOrDefault(ine, cfgT.sinDatoValor());
      double compEstructural = Math.min(100.0, Math.max(0.0, parteEstatica * ft));
      double compVulnerab = num(f, "cv");
      boolean r303030 = Boolean.TRUE.equals(f.get("r303030"));

      double indice = Indice.calcular(compMeteo, compEstructural, compVulnerab, params.indice());
      int nivel = Indice.nivel(indice, params.indice().niveles());

      jdbc.update(
          UPSERT,
          ine,
          fecha,
          compMeteo,
          compEstructural,
          compVulnerab,
          indice,
          nivel,
          r303030,
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

  /**
   * Invariantes: 135 filas para la fecha, sin NULL en índice/nivel (los CHECK ya fuerzan rangos).
   */
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

  /**
   * Backfill del índice para TODA la serie (2005→hoy), los 135 municipios. Sin red: todo sale de
   * fwi_municipio, fwi_climatologia, estructural_municipio y vulnerab_municipio. f_tiempo es
   * dependiente de FECHA (años desde el incendio ANTERIOR a cada día). Idempotente (upsert) y
   * reanudable (batch por municipio); refresca mv_indice_hoy al final.
   */
  public BackfillResultado backfillHistorico(ModeloParams params) {
    String version = params.version();
    ModeloParams.FTiempo cfgT = params.fTiempo();
    ModeloParams.Indice cfgI = params.indice();
    Map<String, List<LocalDate>> fuegos = fuegosCualifican(cfgT.repartoMinFracForestal());
    List<String> munis =
        jdbc.queryForList("select ine_code from municipio order by ine_code", String.class);
    int filas = 0;
    for (String ine : munis) {
      filas += backfillMunicipio(ine, version, cfgT, cfgI, fuegos.getOrDefault(ine, List.of()));
    }
    jdbc.execute("refresh materialized view mv_indice_hoy");
    Map<String, Object> r =
        jdbc.queryForMap(
            "select min(fecha) desde, max(fecha) hasta"
                + " from indice_peligro where version_modelo = ?",
            version);
    return new BackfillResultado(
        munis.size(),
        filas,
        ((java.sql.Date) r.get("desde")).toLocalDate(),
        ((java.sql.Date) r.get("hasta")).toLocalDate());
  }

  private int backfillMunicipio(
      String ine,
      String version,
      ModeloParams.FTiempo cfgT,
      ModeloParams.Indice cfgI,
      List<LocalDate> fuegos) {
    Map<Integer, double[]> clima = cargarClimatologia(ine, version);
    Map<String, Object> f = jdbc.queryForMap(Q_FACT_MUNI, ine);
    double parteEstatica =
        Estructural.parteEstatica(num(f, "ff"), num(f, "co"), num(f, "pm"), num(f, "fp"));
    double compVulnerab = num(f, "cv");

    List<Object[]> batch = new ArrayList<>();
    jdbc.query(
        Q_SERIE,
        (java.sql.ResultSet rs) -> {
          LocalDate fecha = rs.getObject("fecha", LocalDate.class);
          int doy = rs.getInt("doy");
          double[] bp = clima.get(doy);
          if (bp == null) {
            throw new IllegalStateException("sin climatología para " + ine + " doy " + doy);
          }
          double compMeteo = Climatologia.percentil(rs.getDouble("fwi"), bp);
          var anios = Regeneracion.aniosDesdeUltimoIncendio(fecha, fuegos);
          double ft =
              anios.isPresent()
                  ? Regeneracion.fTiempo(anios.getAsInt(), cfgT)
                  : cfgT.sinDatoValor();
          double compEstructural = Math.min(100.0, Math.max(0.0, parteEstatica * ft));
          double indice = Indice.calcular(compMeteo, compEstructural, compVulnerab, cfgI);
          int nivel = Indice.nivel(indice, cfgI.niveles());
          boolean r303030 =
              rs.getDouble("t") >= 30 && rs.getDouble("h") <= 30 && rs.getDouble("v") >= 30;
          batch.add(
              new Object[] {
                ine,
                fecha,
                compMeteo,
                compEstructural,
                compVulnerab,
                indice,
                nivel,
                r303030,
                version
              });
        },
        ine);
    if (batch.isEmpty()) {
      return 0;
    }
    jdbc.batchUpdate(UPSERT, batch);
    return batch.size();
  }

  private Map<Integer, double[]> cargarClimatologia(String ine, String version) {
    Map<Integer, double[]> out = new HashMap<>();
    jdbc.query(
        "select doy, breakpoints from fwi_climatologia where ine_code = ? and version_modelo = ?",
        (java.sql.ResultSet rs) -> {
          Object[] vals = (Object[]) rs.getArray("breakpoints").getArray();
          double[] bp = new double[vals.length];
          for (int i = 0; i < vals.length; i++) {
            bp[i] = ((Number) vals[i]).doubleValue();
          }
          out.put(rs.getInt("doy"), bp);
        },
        ine,
        version);
    return out;
  }

  private Map<String, List<LocalDate>> fuegosCualifican(double reparto) {
    Map<String, List<LocalDate>> out = new HashMap<>();
    jdbc.query(
        Q_FUEGOS,
        (java.sql.ResultSet rs) ->
            out.computeIfAbsent(rs.getString("ine_code"), k -> new ArrayList<>())
                .add(rs.getObject("fecha_inicio", LocalDate.class)),
        reparto);
    return out;
  }

  /**
   * Invariantes del backfill: filas == FWI no-calentamiento, nivel coherente, invariante validado.
   */
  public List<String> asercionesBackfill(String version) {
    List<String> fallos = new ArrayList<>();
    Integer esperadas =
        jdbc.queryForObject(
            "select count(*) from fwi_municipio where not calentamiento", Integer.class);
    Integer filas =
        jdbc.queryForObject(
            "select count(*) from indice_peligro where version_modelo = ?", Integer.class, version);
    if (filas == null || !filas.equals(esperadas)) {
      fallos.add(filas + " filas, esperaba " + esperadas + " (FWI no-calentamiento)");
    }
    Double vall =
        jdbc.queryForObject(
            "select indice from indice_peligro where ine_code = '12126' and fecha = '2026-08-16'",
            Double.class);
    if (vall == null || Math.abs(vall - 32.52) > 0.05) {
      fallos.add("la Vall d'Uixó 2026-08-16 = " + vall + ", esperaba 32.52");
    }
    Integer incoherentes =
        jdbc.queryForObject(
            "select count(*) from indice_peligro where nivel <> case"
                + " when indice <= 20 then 1 when indice <= 40 then 2 when indice <= 60 then 3"
                + " when indice <= 80 then 4 else 5 end",
            Integer.class);
    if (incoherentes != null && incoherentes > 0) {
      fallos.add(incoherentes + " filas con nivel incoherente con el índice");
    }
    return fallos;
  }
}
