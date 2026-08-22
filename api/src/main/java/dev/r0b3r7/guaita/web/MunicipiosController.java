package dev.r0b3r7.guaita.web;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Índice de peligro por municipio (docs/06, ADR-06). El estado dinámico va aquí en JSON —NO en la
 * tesela, que es inmutable— y el cliente lo une por {@code ine_code}. El JSON lo arma PostgreSQL
 * (json_build_object), como en {@link MapaController}. Caché corta con ETag: el índice cambia una
 * vez al día. Si no hay índice se lanza {@link IndiceObsoletoException} (503 con {@code
 * meta.obsoleto}), NUNCA ceros ni vacío silencioso: un índice en cero sin explicación es peligroso.
 */
@RestController
class MunicipiosController {

  // Lista con el índice más reciente. `banderas` con la MISMA forma que el detalle; vientoAlineado
  // sale null (aún no hay dirección de viento en el histórico), nunca false.
  private static final String LISTA_SQL =
      """
      select json_build_object(
        'data', coalesce(json_agg(json_build_object(
           'ineCode', ine_code, 'nombre', nombre, 'comarca', comarca,
           'indice', indice, 'nivel', nivel, 'fecha', fecha,
           'banderas', json_build_object(
              'regla303030', alerta_30_30_30, 'vientoAlineado', viento_alineado))
           order by nombre), '[]'::json),
        'meta', json_build_object(
           'fecha', max(fecha), 'versionModelo', max(version_modelo), 'aviso', ?::text))::text
      from mv_indice_hoy join municipio using (ine_code)
      """;

  // Detalle: tres componentes, FWI del día, serie de 30 días, banderas y calidadDato (real, desde
  // meteo_municipio + estructural_municipio: delta_altitud alto = menos fiable, docs/06).
  private static final String DETALLE_SQL =
      """
      select json_build_object(
        'ineCode', m.ine_code, 'nombre', m.nombre, 'comarca', m.comarca,
        'fecha', mv.fecha, 'indice', mv.indice, 'nivel', mv.nivel,
        'componentes', json_build_object(
           'meteo', mv.comp_meteo, 'estructural', mv.comp_estructural,
           'vulnerabilidad', mv.comp_vulnerab),
        'fwi', json_build_object(
           'ffmc', f.ffmc, 'dmc', f.dmc, 'dc', f.dc, 'isi', f.isi, 'bui', f.bui, 'fwi', f.fwi),
        'banderas', json_build_object(
           'regla303030', mv.alerta_30_30_30, 'vientoAlineado', mv.viento_alineado),
        'calidadDato', json_build_object(
           'deltaAltitudM', me.delta_altitud_m, 'elevacionCeldaM', me.elevacion_celda_m,
           'fracSinCombustible', e.frac_sin_combustible),
        'serie30d', (
           select coalesce(json_agg(json_build_object(
                    'fecha', s.fecha, 'indice', s.indice, 'fwi', fw.fwi) order by s.fecha), '[]'::json)
           from indice_peligro s
           join fwi_municipio fw on fw.ine_code = s.ine_code and fw.fecha = s.fecha
           where s.ine_code = m.ine_code and s.fecha > mv.fecha - 30),
        'meta', json_build_object(
           'fecha', mv.fecha, 'versionModelo', mv.version_modelo, 'aviso', ?::text))::text
      from municipio m
      join mv_indice_hoy mv on mv.ine_code = m.ine_code
      join fwi_municipio f on f.ine_code = m.ine_code and f.fecha = mv.fecha
      join meteo_municipio me on me.ine_code = m.ine_code and me.fecha = mv.fecha
      join estructural_municipio e on e.ine_code = m.ine_code
      where m.ine_code = ?
      """;

  private final JdbcTemplate jdbc;

  MunicipiosController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping(value = "/api/v1/municipios", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<String> lista(
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
    Map<String, Object> meta =
        jdbc.queryForMap(
            "select max(fecha)::text f, max(version_modelo) v, count(*) n from mv_indice_hoy");
    if (((Number) meta.get("n")).longValue() == 0) {
      throw new IndiceObsoletoException(ultimaFecha());
    }
    String etag = etag(meta.get("f") + "-" + meta.get("v"));
    CacheControl cache = cacheDiaria();
    if (etag.equals(ifNoneMatch)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(cache).build();
    }
    String json = jdbc.queryForObject(LISTA_SQL, String.class, Avisos.EMERGENCIAS);
    return ResponseEntity.ok()
        .eTag(etag)
        .cacheControl(cache)
        .contentType(MediaType.APPLICATION_JSON)
        .body(json);
  }

  @GetMapping(value = "/api/v1/municipios/{ineCode}", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<String> detalle(
      @PathVariable String ineCode,
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
    Integer existe =
        jdbc.queryForObject(
            "select count(*) from municipio where ine_code = ?", Integer.class, ineCode);
    if (existe == null || existe == 0) {
      throw new RecursoNoEncontradoException("Municipio " + ineCode + " inexistente.");
    }
    List<Map<String, Object>> filas =
        jdbc.queryForList(
            "select fecha::text f, version_modelo v from mv_indice_hoy where ine_code = ?",
            ineCode);
    if (filas.isEmpty()) {
      throw new IndiceObsoletoException(ultimaFecha());
    }
    Map<String, Object> mv = filas.get(0);
    String etag = etag(ineCode + "-" + mv.get("f") + "-" + mv.get("v"));
    CacheControl cache = cacheDiaria();
    if (etag.equals(ifNoneMatch)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(cache).build();
    }
    String json = jdbc.queryForObject(DETALLE_SQL, String.class, Avisos.EMERGENCIAS, ineCode);
    return ResponseEntity.ok()
        .eTag(etag)
        .cacheControl(cache)
        .contentType(MediaType.APPLICATION_JSON)
        .body(json);
  }

  private String ultimaFecha() {
    return jdbc.queryForObject("select max(fecha)::text from indice_peligro", String.class);
  }

  private static CacheControl cacheDiaria() {
    return CacheControl.maxAge(Duration.ofHours(1))
        .cachePublic()
        .staleWhileRevalidate(Duration.ofDays(1));
  }

  private static String etag(String contenido) {
    return "\"" + contenido + "\"";
  }
}
