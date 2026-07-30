package dev.r0b3r7.guaita.web.tiles;

import java.time.Duration;
import java.util.zip.CRC32;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint de teselas vectoriales de municipios (ADR-04). Sirve MVT crudo desde PostGIS; la
 * validación de {@code z/x/y} la hace {@link TileMath} (z fuera de [0,16] o índices fuera de rango
 * -> {@link IllegalArgumentException} -> 400, ver {@code ApiExceptionHandler}).
 */
@RestController
class MunicipioTilesController {

  /** Tipo MIME oficial de un tile vectorial de Mapbox. */
  static final String MVT_TYPE = "application/vnd.mapbox-vector-tile";

  private final MunicipioTileRepository repo;

  MunicipioTilesController(MunicipioTileRepository repo) {
    this.repo = repo;
  }

  @GetMapping(value = "/api/v1/tiles/municipios/{z}/{x}/{y}.mvt", produces = MVT_TYPE)
  ResponseEntity<byte[]> municipios(
      @PathVariable int z,
      @PathVariable int x,
      @PathVariable int y,
      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

    TileMath.Bbox bbox = TileMath.tileBounds(z, x, y); // valida rangos -> 400 si no
    byte[] tile = repo.municipiosTile(bbox);

    // Sin geometría: 204, nunca 404 ni un MVT vacío con pinta de error (contrato docs/06).
    if (tile == null || tile.length == 0) {
      return ResponseEntity.noContent().build();
    }

    // ADR-06: las teselas llevan SOLO geometría e identidad (ine_code/nombre/comarca), que son
    // inmutables entre seeds; los datos dinámicos (índice, nivel) viajan por JSON aparte y se unen
    // en cliente por ine_code. Así se cachean un año y actualizar el índice no invalida teselas.
    // ETag fuerte por contenido (cambia tras un re-seed) para revalidar si el cliente lo pide.
    String etag = "\"" + Long.toHexString(crc32(tile)) + "\"";
    CacheControl cache = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();
    if (etag.equals(ifNoneMatch)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(cache).build();
    }
    return ResponseEntity.ok()
        .eTag(etag)
        .cacheControl(cache)
        .contentType(MediaType.parseMediaType(MVT_TYPE))
        .body(tile);
  }

  private static long crc32(byte[] bytes) {
    CRC32 crc = new CRC32();
    crc.update(bytes);
    return crc.getValue();
  }
}
