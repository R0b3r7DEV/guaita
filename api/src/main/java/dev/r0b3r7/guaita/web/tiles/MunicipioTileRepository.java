package dev.r0b3r7.guaita.web.tiles;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Genera el tile MVT de la capa {@code municipios} desde PostGIS con {@code ST_AsMVT} (ADR-04, sin
 * pg_tileserv). Consume {@code municipio.geom} —la geometría ADMINISTRATIVA, con Columbretes: el
 * mapa real del territorio—. Solo viajan {@code ine_code}, {@code nombre} y {@code comarca}: el
 * índice de peligro no existe hasta la Fase 3.
 */
@Repository
class MunicipioTileRepository {

  /** Resolución de la rejilla del tile (extent MVT estándar). */
  static final int EXTENT = 4096;

  // Presupuesto de CPU por tile en PostGIS (T4, docs/07): si una consulta se pasa, el servidor la
  // cancela en vez de dejar la conexión colgada. SET LOCAL -> se revierte al cerrar la transacción.
  private static final String STATEMENT_TIMEOUT_MS = "3000";

  // Filtro por índice GiST: se transforma la ENVOLVENTE a 25830 (una vez) para que `&&` use el
  // índice espacial de municipio.geom. Reproyectar cada geometría al revés no usaría el índice.
  // ST_AsMVTGeom trabaja en 3857. Simplificación: tolerancia = una celda MVT (ancho/extent), que
  // coincide con la resolución de salida, así que nunca borra detalle visible; con 135 polígonos
  // el coste de reproyectar vértices es despreciable frente a preservar la topología.
  private static final String SQL =
      """
      with b as (select st_makeenvelope(?, ?, ?, ?, 3857) as g)
      select st_asmvt(q, 'municipios', 4096, 'geom')
      from (
        select
          st_asmvtgeom(
            st_simplifypreservetopology(st_transform(m.geom, 3857), ?),
            b.g, 4096, 64, true) as geom,
          m.ine_code, m.nombre, m.comarca
        from municipio m cross join b
        where m.geom && st_transform(b.g, 25830)
      ) as q
      where q.geom is not null
      """;

  private final JdbcTemplate jdbc;

  MunicipioTileRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Tile MVT de la envolvente dada (EPSG:3857). Devuelve {@code null} si ningún municipio cae en la
   * tesela: el controlador lo traduce a 204, no a un MVT vacío.
   */
  @Transactional(readOnly = true)
  byte[] municipiosTile(TileMath.Bbox b) {
    jdbc.execute("set local statement_timeout = " + STATEMENT_TIMEOUT_MS);
    double tol = (b.maxX() - b.minX()) / EXTENT; // una celda MVT, en metros de 3857
    return jdbc.queryForObject(SQL, byte[].class, b.minX(), b.minY(), b.maxX(), b.maxY(), tol);
  }
}
