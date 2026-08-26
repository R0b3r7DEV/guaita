package dev.r0b3r7.guaita.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduce errores de la API a {@code application/problem+json} (RFC 7807, docs/06). Mensajes
 * GENÉRICOS en el 500: NUNCA se devuelve el mensaje, la clase Java ni el stack (repo y servicio
 * públicos, docs/07). El detalle real se registra en el log del servidor, no en la respuesta.
 */
@RestControllerAdvice
class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail parametrosInvalidos(IllegalArgumentException ex) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    pd.setTitle("Parámetros inválidos");
    return pd;
  }

  @ExceptionHandler(RecursoNoEncontradoException.class)
  ProblemDetail noEncontrado(RecursoNoEncontradoException ex) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    pd.setTitle("No encontrado");
    return pd;
  }

  @ExceptionHandler(IndiceObsoletoException.class)
  ProblemDetail obsoleto(IndiceObsoletoException ex) {
    ProblemDetail pd =
        ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    pd.setTitle("Índice obsoleto");
    pd.setProperty("obsoleto", true);
    pd.setProperty("ultimaFecha", ex.ultimaFecha);
    pd.setProperty("aviso", Avisos.EMERGENCIAS);
    return pd;
  }

  @ExceptionHandler(AccessDeniedException.class)
  ProblemDetail accesoDenegado(AccessDeniedException ex) {
    ProblemDetail pd =
        ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "No autorizado para este recurso.");
    pd.setTitle("Acceso denegado");
    return pd;
  }

  /** Catch-all: 500 genérico. El detalle va al log del servidor, nunca a la respuesta. */
  @ExceptionHandler(Exception.class)
  ProblemDetail interno(Exception ex) {
    log.error("error no controlado en la API", ex);
    ProblemDetail pd =
        ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno.");
    pd.setTitle("Error interno");
    return pd;
  }
}
