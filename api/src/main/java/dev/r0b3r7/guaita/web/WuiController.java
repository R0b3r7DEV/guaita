package dev.r0b3r7.guaita.web;

import dev.r0b3r7.guaita.auth.RateLimiter;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Interfaz urbano-forestal (docs/05, docs/06). Dos niveles de acceso (T2 de docs/07):
 *
 * <ul>
 *   <li><b>Agregado por municipio: PÚBLICO.</b> Recuentos por clase (interés general, no identifica
 *       a nadie). {@code advertenciaMargen} va etiquetada como CAUTELA TÉCNICA, no incumplimiento.
 *   <li><b>Detalle por edificación: AUTENTICADO (JWT).</b> Solo referencia catastral y coordenada,
 *       nunca titularidad ni direcciones. Un técnico solo ve SU término; otro término -&gt; 403.
 * </ul>
 */
@RestController
class WuiController {

  private static final String DESCARGO =
      "Estimación geométrica automatizada a partir de cartografía oficial (Catastro INSPIRE +"
          + " PATFOR + MDT25). NO constituye certificación de cumplimiento normativo, que"
          + " corresponde al órgano competente previa inspección. La pendiente sale del MDT25"
          + " (25 m): el relieve se suaviza, así que el análisis SUBESTIMA la franja de 50 m en"
          + " ladera, no la sobreestima. Franja: Anexo XI del TRLOTUP (30 m; 50 m si pendiente >"
          + " 30 %).";

  private static final String AGREGADO_SQL =
      """
      select json_build_object(
        'ineCode', ?::text,
        'total', count(*),
        'porClase', json_build_object(
           'critico', count(*) filter (where clase = 'critico'),
           'incumple', count(*) filter (where clase = 'incumple'),
           'cumple', count(*) filter (where clase = 'cumple')),
        'advertenciaMargen', count(*) filter (where advertencia_margen),
        'franja50Pendiente', count(*) filter (where franja_m = 50),
        'nota', 'advertenciaMargen es una CAUTELA TÉCNICA (cumple, pero cerca), no un incumplimiento',
        'versionAnalisis', max(version_analisis),
        'descargo', ?::text)::text
      from wui_edificacion where ine_code = ?
      """;

  // Detalle: SOLO referencia catastral + coordenada (4326) + clasificación. NADA de titularidad.
  private static final String DETALLE_SQL =
      """
      select json_build_object(
        'ineCode', ?::text,
        'descargo', ?::text,
        'edificaciones', coalesce(json_agg(json_build_object(
           'refCatastral', e.ref_catastral,
           'lat', round(st_y(st_transform(st_pointonsurface(e.geom), 4326))::numeric, 6),
           'lon', round(st_x(st_transform(st_pointonsurface(e.geom), 4326))::numeric, 6),
           'clase', w.clase, 'distForestalM', w.dist_forestal_m, 'franjaM', w.franja_m,
           'advertenciaMargen', w.advertencia_margen)
           order by w.dist_forestal_m asc nulls last), '[]'::json))::text
      from wui_edificacion w
      join edificacion e on e.ref_catastral = w.ref_catastral
      where w.ine_code = ?
      """;

  private final JdbcTemplate jdbc;
  private final RateLimiter rateLimiter;

  WuiController(JdbcTemplate jdbc, RateLimiter rateLimiter) {
    this.jdbc = jdbc;
    this.rateLimiter = rateLimiter;
  }

  @GetMapping(value = "/api/v1/wui/agregado/{ineCode}", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<String> agregado(@PathVariable String ineCode) {
    existeMunicipio(ineCode);
    String json = jdbc.queryForObject(AGREGADO_SQL, String.class, ineCode, DESCARGO, ineCode);
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json);
  }

  @GetMapping(
      value = "/api/v1/wui/municipio/{ineCode}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<String> detalle(
      @PathVariable String ineCode, @AuthenticationPrincipal Jwt jwt) {
    // Autorización por término: admin ve todos; el técnico solo el suyo (si no, 403).
    boolean admin = "admin".equals(jwt.getClaimAsString("rol"));
    if (!admin && !ineCode.equals(jwt.getClaimAsString("ine"))) {
      throw new AccessDeniedException("No autorizado para el término " + ineCode);
    }
    if (!rateLimiter.permite("wui-detalle:" + jwt.getSubject(), 60, Duration.ofMinutes(1))) {
      return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
    }
    existeMunicipio(ineCode);
    String json = jdbc.queryForObject(DETALLE_SQL, String.class, ineCode, DESCARGO, ineCode);
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json);
  }

  private void existeMunicipio(String ineCode) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from municipio where ine_code = ?", Integer.class, ineCode);
    if (n == null || n == 0) {
      throw new RecursoNoEncontradoException("Municipio " + ineCode + " inexistente.");
    }
  }
}
