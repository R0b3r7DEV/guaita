package dev.r0b3r7.guaita.web;

import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Metadatos del mapa (ADR-06). {@code extent} devuelve la envolvente CONTINENTAL de la provincia en
 * EPSG:4326, tomada de {@code mv_provincia_continental} —NO de la administrativa: esta incluye las
 * Columbretes y arrancaría el visor mostrando ~50 km de mar abierto—. Encuadre estático; los datos
 * dinámicos (índice) irán por endpoints JSON aparte y se unirán en cliente por {@code ine_code}.
 */
@RestController
class MapaController {

  // El JSON lo arma PostgreSQL (json_build_object/array): una consulta escalar, sin mapear filas a
  // arrays en Java. Con la BD sin sembrar, ST_Extent es NULL y devuelve bbox:[null,null,null,null].
  private static final String EXTENT_SQL =
      "select json_build_object('bbox', json_build_array("
          + "st_xmin(e), st_ymin(e), st_xmax(e), st_ymax(e)))::text "
          + "from (select st_extent(st_transform(geom, 4326)) e from mv_provincia_continental) t";

  private final JdbcTemplate jdbc;

  MapaController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping(value = "/api/v1/mapa/extent", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<String> extent() {
    String json = jdbc.queryForObject(EXTENT_SQL, String.class);
    // El encuadre solo cambia tras un re-seed de límites municipales: cachea un día.
    CacheControl cache = CacheControl.maxAge(Duration.ofDays(1)).cachePublic();
    return ResponseEntity.ok().cacheControl(cache).body(json);
  }
}
