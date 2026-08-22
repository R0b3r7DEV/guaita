package dev.r0b3r7.guaita.web;

/** Textos de aviso compartidos por la API. */
final class Avisos {

  private Avisos() {}

  /** No es un sistema de emergencias (T7, docs/07). Va en el meta de las respuestas del índice. */
  static final String EMERGENCIAS =
      "GUAITA es una herramienta analitica; NO es un sistema de emergencias."
          + " Ante un incendio, llame al 112.";
}
