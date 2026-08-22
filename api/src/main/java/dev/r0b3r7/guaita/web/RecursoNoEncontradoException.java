package dev.r0b3r7.guaita.web;

/** El recurso pedido no existe (p. ej. un {@code ineCode} desconocido) → 404. */
class RecursoNoEncontradoException extends RuntimeException {

  RecursoNoEncontradoException(String mensaje) {
    super(mensaje);
  }
}
