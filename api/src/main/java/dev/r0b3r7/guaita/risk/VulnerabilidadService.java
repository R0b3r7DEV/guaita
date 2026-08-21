package dev.r0b3r7.guaita.risk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Precálculo del componente de vulnerabilidad PROVISIONAL por municipio (tabla {@code
 * vulnerab_municipio}, docs/04 §3). PostGIS calcula las áreas y {@link Vulnerabilidad} combina. Se
 * recalcula cuando cambia la cartografía o la versión del modelo, no a diario.
 */
@Service
public class VulnerabilidadService {

  // protegido_area: unión de los solapes de espacio_protegido con el municipio (st_union para no
  // duplicar donde RN2000 y ENP coinciden). muni_cont_area: municipio CONTINENTAL (sin islas: las
  // Columbretes no cuentan, docs/04 §3). poblacion: para la normalización.
  private static final String Q_AREAS =
      """
      select
        m.poblacion,
        coalesce((select st_area(st_union(st_intersection(ep.geom, m.geom)))
                  from espacio_protegido ep
                  where ep.geom && m.geom and st_intersects(ep.geom, m.geom)), 0) as protegido_area,
        st_area(st_intersection(m.geom, (select geom from mv_provincia_continental))) as muni_cont_area
      from municipio m where m.ine_code = ?
      """;

  private static final String UPSERT =
      """
      insert into vulnerab_municipio
        (ine_code, poblacion_norm, frac_espacio_protegido, comp_vulnerab, version_modelo)
      values (?, ?, ?, ?, ?)
      on conflict (ine_code) do update set
        poblacion_norm = excluded.poblacion_norm,
        frac_espacio_protegido = excluded.frac_espacio_protegido,
        comp_vulnerab = excluded.comp_vulnerab, version_modelo = excluded.version_modelo,
        calculado_en = now()
      """;

  /** Filas escritas y hectáreas de suelo protegido dentro del límite provincial estricto. */
  public record Resultado(int filas, double protegidoHa) {}

  private final JdbcTemplate jdbc;

  public VulnerabilidadService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Recalcula {@code vulnerab_municipio} para una versión. Idempotente (upsert por municipio). */
  @Transactional
  public Resultado precomputar(ModeloParams params) {
    String version = params.version();
    ModeloParams.Vulnerab cfg = params.vulnerab();
    int poblacionMax =
        jdbc.queryForObject("select max(poblacion) from municipio", Integer.class);
    List<String> munis =
        jdbc.queryForList("select ine_code from municipio order by ine_code", String.class);
    int filas = 0;
    double protegidoArea = 0;
    for (String ine : munis) {
      Map<String, Object> areas = jdbc.queryForMap(Q_AREAS, ine);
      int poblacion = ((Number) areas.get("poblacion")).intValue();
      double protArea = ((Number) areas.get("protegido_area")).doubleValue();
      double muniCont = ((Number) areas.get("muni_cont_area")).doubleValue();
      double fracProt = muniCont > 0 ? Math.min(1.0, protArea / muniCont) : 0.0;
      double poblacionNorm = Vulnerabilidad.poblacionNorm(poblacion, poblacionMax);
      double comp = Vulnerabilidad.compVulnerab(poblacionNorm, fracProt, cfg);

      jdbc.update(UPSERT, ine, poblacionNorm, fracProt, comp, version);
      filas++;
      protegidoArea += protArea;
    }
    return new Resultado(filas, protegidoArea / 10000.0);
  }

  /** Invariantes: una fila por municipio (los rangos ya los fuerza el CHECK del esquema). */
  public List<String> asserciones(String version) {
    List<String> fallos = new ArrayList<>();
    Integer provincia = jdbc.queryForObject("select count(*) from municipio", Integer.class);
    Integer filas =
        jdbc.queryForObject(
            "select count(*) from vulnerab_municipio where version_modelo = ?",
            Integer.class,
            version);
    if (filas == null || !filas.equals(provincia)) {
      fallos.add(filas + " filas, esperaba " + provincia);
    }
    return fallos;
  }
}
