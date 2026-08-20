package dev.r0b3r7.guaita.risk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Precálculo de la parte ESTÁTICA del componente estructural por municipio ({@code
 * estructural_municipio}, docs/04 §2). PostGIS calcula las áreas; {@link Estructural} combina. Se
 * recalcula cuando cambia la cartografía o la versión del modelo, no a diario.
 */
@Service
public class EstructuralService {

  // frac_forestal: numerador = forestal ∩ municipio; denominador = municipio CONTINENTAL (sin islas:
  // Castelló no debe inflar el denominador con las Columbretes, docs/03).
  private static final String Q_AREAS =
      """
      select
        coalesce((select sum(st_area(st_intersection(f.geom, m.geom))) from terreno_forestal f
                  where f.geom && m.geom and st_intersects(f.geom, m.geom)), 0) as forestal_area,
        st_area(st_intersection(m.geom, (select geom from mv_provincia_continental))) as muni_cont_area
      from municipio m where m.ine_code = ?
      """;

  // continuidad: forestal ∩ (municipio + 2 km) re-unido (la capa viene ST_Subdivide de Fase 1),
  // componentes conexos, mayor / total. El buffer evita cortes en la línea jurisdiccional (docs/04).
  private static final String Q_CONTINUIDAD =
      """
      with b as (select st_buffer(geom, 2000) g from municipio where ine_code = ?),
        uni as (select st_union(f.geom) u from terreno_forestal f
                join b on f.geom && b.g and st_intersects(f.geom, b.g)),
        u as (select st_intersection(uni.u, b.g) geom from uni, b),
        d as (select (st_dump(geom)).geom g from u)
      select coalesce(max(st_area(g)) / nullif((select st_area(geom) from u), 0), 0) from d
      """;

  // Área forestal (dentro del municipio) cubierta por cada código Anderson. Partición sin solape:
  // el mapa de combustible no se solapa consigo mismo, así que sumar por código no duplica.
  private static final String Q_PESO_POR_CODIGO =
      """
      with fm as (select st_intersection(f.geom, m.geom) g from terreno_forestal f
                  join municipio m on m.ine_code = ? where f.geom && m.geom and st_intersects(f.geom, m.geom))
      select mc.codigo_origen, sum(st_area(st_intersection(fm.g, mc.geom))) area
      from fm join modelo_combustible_patfor mc on fm.g && mc.geom and st_intersects(fm.g, mc.geom)
      group by mc.codigo_origen
      """;

  private static final String UPSERT =
      """
      insert into estructural_municipio
        (ine_code, frac_forestal, continuidad, peso_modelo, frac_sin_combustible, f_pendiente,
         version_modelo)
      values (?, ?, ?, ?, ?, ?, ?)
      on conflict (ine_code) do update set
        frac_forestal = excluded.frac_forestal, continuidad = excluded.continuidad,
        peso_modelo = excluded.peso_modelo, frac_sin_combustible = excluded.frac_sin_combustible,
        f_pendiente = excluded.f_pendiente, version_modelo = excluded.version_modelo,
        calculado_en = now()
      """;

  /** Total forestal calculado, para el contraste externo con Fase 1 (~422.000 ha). */
  public record Resultado(int filas, double totalForestalHa) {}

  private final JdbcTemplate jdbc;

  public EstructuralService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Recalcula {@code estructural_municipio} para una versión. Idempotente (upsert por municipio). */
  @Transactional
  public Resultado precomputar(ModeloParams params) {
    String version = params.version();
    Map<String, Double> pesos = params.combustible().pesos();
    double pesoDefecto = params.combustible().pesoDefecto();
    List<String> munis =
        jdbc.queryForList("select ine_code from municipio order by ine_code", String.class);
    int filas = 0;
    double totalForestal = 0;
    for (String ine : munis) {
      Map<String, Object> areas = jdbc.queryForMap(Q_AREAS, ine);
      double forestalArea = ((Number) areas.get("forestal_area")).doubleValue();
      double muniCont = ((Number) areas.get("muni_cont_area")).doubleValue();
      double fracForestal = muniCont > 0 ? forestalArea / muniCont : 0.0;
      double continuidad = jdbc.queryForObject(Q_CONTINUIDAD, Double.class, ine);
      double p90 =
          jdbc.queryForObject(
              "select pendiente_p90_pct from topografia_municipio where ine_code = ?",
              Double.class,
              ine);

      Map<String, Double> areaPorCodigo = new LinkedHashMap<>();
      jdbc.query(
          Q_PESO_POR_CODIGO,
          rs -> areaPorCodigo.put(rs.getString("codigo_origen"), rs.getDouble("area")),
          ine);

      double fPendiente = Estructural.fPendiente(p90);
      Estructural.PesoResultado pr =
          Estructural.pesoPonderado(forestalArea, areaPorCodigo, pesos, pesoDefecto);

      jdbc.update(
          UPSERT,
          ine,
          fracForestal,
          continuidad,
          pr.peso(),
          pr.fracSinCombustible(),
          fPendiente,
          version);
      filas++;
      totalForestal += forestalArea;
    }
    return new Resultado(filas, totalForestal / 10000.0);
  }

  /** Invariantes: 135 filas, sin NULL, factores en [0,1] (los CHECK del esquema ya lo fuerzan). */
  public List<String> asserciones(String version) {
    List<String> fallos = new ArrayList<>();
    Integer provincia = jdbc.queryForObject("select count(*) from municipio", Integer.class);
    Integer filas =
        jdbc.queryForObject(
            "select count(*) from estructural_municipio where version_modelo = ?",
            Integer.class,
            version);
    if (filas == null || !filas.equals(provincia)) {
      fallos.add(filas + " filas, esperaba " + provincia);
    }
    return fallos;
  }

  /** Nº de municipios con {@code frac_sin_combustible} por encima de un umbral. */
  public int municipiosSinCombustibleSobre(double umbral) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from estructural_municipio where frac_sin_combustible > ?",
            Integer.class,
            umbral);
    return n == null ? 0 : n;
  }

  /** Nº de municipios cuyo {@code f_pendiente} satura a 1.0. */
  public int municipiosPendienteSaturada() {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from estructural_municipio where f_pendiente >= 1.0", Integer.class);
    return n == null ? 0 : n;
  }
}
