package dev.r0b3r7.guaita.web;

/**
 * No hay índice para servir → 503 con {@code meta.obsoleto} y la última fecha calculada (docs/06).
 * NUNCA devolver ceros ni vacío silencioso: un índice de peligro en cero sin explicación es
 * peligroso en esta aplicación.
 */
class IndiceObsoletoException extends RuntimeException {

  final transient String ultimaFecha;

  IndiceObsoletoException(String ultimaFecha) {
    super("Índice no disponible: aún no se ha calculado ningún día.");
    this.ultimaFecha = ultimaFecha;
  }
}
