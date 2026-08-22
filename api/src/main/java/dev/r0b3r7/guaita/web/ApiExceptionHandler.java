package dev.r0b3r7.guaita.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduce errores de la API a {@code application/problem+json} (RFC 7807, docs/06). Por ahora solo
 * mapea {@link IllegalArgumentException} a 400 (p. ej. z/x/y de tesela fuera de rango en TileMath).
 */
@RestControllerAdvice
class ApiExceptionHandler {

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
}
