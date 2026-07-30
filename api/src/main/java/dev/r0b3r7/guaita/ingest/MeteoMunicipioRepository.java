package dev.r0b3r7.guaita.ingest;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistencia de {@code meteo_municipio} y los puntos de consulta de Open-Meteo.
 *
 * <p>El punto de cada municipio es {@code ST_PointOnSurface} del MAYOR polígono de su geometría, en
 * EPSG:4326. El mayor polígono es el continente: así Castelló (12040) no pide la meteo de las
 * Columbretes (docs/03). {@code ST_PointOnSurface} —no {@code ST_Centroid}— garantiza que el punto
 * cae DENTRO del polígono, cosa que el centroide no asegura en términos con forma irregular.
 */
@Repository
public class MeteoMunicipioRepository {

  private static final String PUNTOS_SQL =
      """
      select ine_code, st_x(pt) as lon, st_y(pt) as lat
      from (
        select m.ine_code, st_transform(st_pointonsurface(g.geom), 4326) as pt
        from municipio m
        cross join lateral (
          select d.geom from st_dump(m.geom) d order by st_area(d.geom) desc limit 1
        ) g
      ) s
      order by ine_code
      """;

  private static final String FUERA_SQL =
      """
      select count(*)
      from municipio m
      cross join lateral (
        select d.geom from st_dump(m.geom) d order by st_area(d.geom) desc limit 1
      ) g
      where not st_contains(m.geom, st_pointonsurface(g.geom))
      """;

  private static final String UPSERT_SQL =
      """
      insert into meteo_municipio
        (ine_code, fecha, temp_12utc_c, hr_12utc_pct, viento_12utc_kmh, precip_24h_mm,
         interpolado, n_estaciones, source, source_url)
      values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      on conflict (ine_code, fecha) do update set
        temp_12utc_c = excluded.temp_12utc_c,
        hr_12utc_pct = excluded.hr_12utc_pct,
        viento_12utc_kmh = excluded.viento_12utc_kmh,
        precip_24h_mm = excluded.precip_24h_mm,
        interpolado = excluded.interpolado,
        n_estaciones = excluded.n_estaciones,
        source = excluded.source,
        source_url = excluded.source_url,
        fetched_at = now()
      """;

  private final JdbcTemplate jdbc;

  public MeteoMunicipioRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Punto de consulta (lon/lat en 4326) de cada municipio, sobre su continente. */
  public List<OpenMeteoClient.Punto> puntosDeConsulta() {
    return jdbc.query(
        PUNTOS_SQL,
        (rs, n) ->
            new OpenMeteoClient.Punto(
                rs.getString("ine_code"), rs.getDouble("lon"), rs.getDouble("lat")));
  }

  /** Nº de municipios cuyo punto de consulta NO cae dentro de su término (debe ser 0). */
  public int municipiosConPuntoFuera() {
    Integer n = jdbc.queryForObject(FUERA_SQL, Integer.class);
    return n == null ? 0 : n;
  }

  /**
   * Inserta o actualiza el lote ENTERO en una transacción (idempotente por {@code (ine_code,
   * fecha)}). Todo o nada: si una fila viola una restricción, no se guarda ninguna (T6, docs/07).
   */
  @Transactional
  public void upsertAll(List<MeteoMunicipio> filas) {
    List<Object[]> args = new ArrayList<>(filas.size());
    for (MeteoMunicipio m : filas) {
      args.add(
          new Object[] {
            m.ineCode(),
            m.fecha(),
            m.temp12utcC(),
            m.hr12utcPct(),
            m.viento12utcKmh(),
            m.precip24hMm(),
            m.interpolado(),
            m.nEstaciones(),
            m.source(),
            m.sourceUrl()
          });
    }
    jdbc.batchUpdate(UPSERT_SQL, args);
  }
}
