package dev.r0b3r7.guaita.risk;

/**
 * Normalización estacional de {@code comp_meteo} (docs/04 §1): traduce un FWI al percentil 0..100
 * de su distribución de referencia local, dada por 101 breakpoints P0..P100 (uno por percentil,
 * ascendentes). Pura y sin estado; el precálculo de los breakpoints y su persistencia viven aparte.
 */
public final class Climatologia {

  private Climatologia() {}

  /**
   * Percentil interpolado (0..100) de {@code fwi} contra {@code breakpoints} (101 valores P0..P100,
   * ascendentes).
   *
   * <ul>
   *   <li>Por debajo de P0 → 0 (no negativo).
   *   <li>Por encima de P100 → 100 (satura; ocurre al evaluar un día récord contra una referencia
   *       que termina antes —p. ej. 2026 vs base 2025).
   *   <li>Igual a un breakpoint → su percentil; entre dos → interpolación lineal.
   *   <li>Breakpoints repetidos (inviernos con muchos FWI≈0 → P0..Pk iguales): devuelve el borde
   *       superior de la meseta, SIN dividir por cero ni NaN (los tramos planos se saltan).
   * </ul>
   */
  public static double percentil(double fwi, double[] breakpoints) {
    if (breakpoints.length != 101) {
      throw new IllegalArgumentException("se esperaban 101 breakpoints, hay " + breakpoints.length);
    }
    if (fwi < breakpoints[0]) {
      return 0.0;
    }
    if (fwi > breakpoints[100]) {
      return 100.0;
    }
    for (int i = 0; i < 100; i++) {
      double lo = breakpoints[i];
      double hi = breakpoints[i + 1];
      // Solo se entra (y se divide) donde lo <= fwi < hi, lo que implica hi > lo: los tramos
      // planos (hi == lo <= fwi) fallan la condición fwi < hi y se saltan.
      if (lo <= fwi && fwi < hi) {
        return i + (fwi - lo) / (hi - lo);
      }
    }
    return 100.0; // fwi == P100 exacto (o meseta en el tope)
  }
}
