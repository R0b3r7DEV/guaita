package dev.r0b3r7.guaita.web.tiles;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Teselas de DETALLE IUF (edificaciones). T2 (docs/07): requiere JWT (lo impone SeguridadConfig) y
 * solo sirve geometría a partir de z &gt;= 14; por debajo devuelve 204 (para el mapa a escala está
 * el agregado municipal, público). Un técnico solo ve SU término; el admin, todos. Cada feature
 * lleva SOLO ref_catastral y clase: nunca titularidad. Caché privada (depende del usuario).
 */
@RestController
class WuiTilesController {

  static final String MVT_TYPE = "application/vnd.mapbox-vector-tile";
  private static final int Z_MIN_DETALLE = 14;

  // admin (?) ve todos; el técnico solo su ine (?). ST_AsMVTGeom en 3857; filtro GiST en 25830.
  private static final String SQL =
      """
      with b as (select st_makeenvelope(?, ?, ?, ?, 3857) as g)
      select st_asmvt(q, 'edificaciones', 4096, 'geom')
      from (
        select st_asmvtgeom(st_transform(e.geom, 3857), b.g, 4096, 64, true) as geom,
               e.ref_catastral, w.clase
        from edificacion e
        join wui_edificacion w on w.ref_catastral = e.ref_catastral
        cross join b
        where e.geom && st_transform(b.g, 25830)
          and (?::boolean or e.ine_code = ?)
      ) as q
      where q.geom is not null
      """;

  private final JdbcTemplate jdbc;

  WuiTilesController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping(value = "/api/v1/tiles/wui/{z}/{x}/{y}.mvt", produces = MVT_TYPE)
  @Transactional(readOnly = true)
  ResponseEntity<byte[]> detalle(
      @PathVariable int z,
      @PathVariable int x,
      @PathVariable int y,
      @AuthenticationPrincipal Jwt jwt) {
    TileMath.Bbox b = TileMath.tileBounds(z, x, y); // valida rangos -> 400
    if (z < Z_MIN_DETALLE) {
      return ResponseEntity.noContent().build(); // sin detalle a escala pequeña
    }
    boolean admin = "admin".equals(jwt.getClaimAsString("rol"));
    String ine = jwt.getClaimAsString("ine");
    jdbc.execute("set local statement_timeout = 3000");
    byte[] tile =
        jdbc.queryForObject(
            SQL, byte[].class, b.minX(), b.minY(), b.maxX(), b.maxY(), admin, ine);
    if (tile == null || tile.length == 0) {
      return ResponseEntity.noContent().build();
    }
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore().cachePrivate())
        .contentType(MediaType.parseMediaType(MVT_TYPE))
        .body(tile);
  }
}
