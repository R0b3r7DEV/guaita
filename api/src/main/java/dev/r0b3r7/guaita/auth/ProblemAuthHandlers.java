package dev.r0b3r7.guaita.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 401/403 como {@code application/problem+json} (RFC 7807), con mensajes GENÉRICOS: nada de clases
 * Java, rutas internas ni el motivo exacto del fallo del token (docs/07). El repo y el servicio son
 * públicos: un error verboso es información gratis. Un token expirado o con firma manipulada acaba
 * aquí como 401 (no 500), porque el resource server lanza una AuthenticationException.
 */
@Component
class ProblemAuthHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

  private final ObjectMapper json;

  ProblemAuthHandlers(ObjectMapper json) {
    this.json = json;
  }

  @Override
  public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException ex)
      throws IOException {
    escribir(res, HttpStatus.UNAUTHORIZED, "No autenticado", "Se requiere autenticación válida.");
  }

  @Override
  public void handle(HttpServletRequest req, HttpServletResponse res, AccessDeniedException ex)
      throws IOException {
    escribir(res, HttpStatus.FORBIDDEN, "Acceso denegado", "No autorizado para este recurso.");
  }

  private void escribir(HttpServletResponse res, HttpStatus estado, String titulo, String detalle)
      throws IOException {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(estado, detalle);
    pd.setTitle(titulo);
    res.setStatus(estado.value());
    res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    json.writeValue(res.getOutputStream(), pd);
  }
}
