package dev.r0b3r7.guaita.wui;

import dev.r0b3r7.guaita.auth.RateLimiter;
import java.time.Duration;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
 * Informe municipal IUF en PDF (docs/05, docs/06). Es el único punto del proyecto que hace una
 * afirmación cuasi-normativa sobre propiedades concretas; por eso va tras JWT, SOLO del término
 * autorizado (un técnico de otro municipio recibe 403, igual que en el detalle JSON) y con rate
 * limit estricto (10/hora, docs/07). Solo referencia catastral y coordenada; nunca titularidad.
 */
@RestController
class WuiInformeController {

  private final InformeMunicipalService informes;
  private final JdbcTemplate jdbc;
  private final RateLimiter rateLimiter;

  WuiInformeController(
      InformeMunicipalService informes, JdbcTemplate jdbc, RateLimiter rateLimiter) {
    this.informes = informes;
    this.jdbc = jdbc;
    this.rateLimiter = rateLimiter;
  }

  @GetMapping("/api/v1/wui/informe/{ineCode}.pdf")
  ResponseEntity<byte[]> informe(
      @PathVariable String ineCode, @AuthenticationPrincipal Jwt jwt) {
    boolean admin = "admin".equals(jwt.getClaimAsString("rol"));
    if (!admin && !ineCode.equals(jwt.getClaimAsString("ine"))) {
      throw new AccessDeniedException("No autorizado para el término " + ineCode);
    }
    if (!rateLimiter.permite("wui-informe:" + jwt.getSubject(), 10, Duration.ofHours(1))) {
      return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
    }
    Integer n =
        jdbc.queryForObject(
            "select count(*) from municipio where ine_code = ?", Integer.class, ineCode);
    if (n == null || n == 0) {
      return ResponseEntity.notFound().build();
    }
    byte[] pdf = informes.generar(ineCode);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename("iuf-" + ineCode + ".pdf")
                .build()
                .toString())
        .body(pdf);
  }
}
