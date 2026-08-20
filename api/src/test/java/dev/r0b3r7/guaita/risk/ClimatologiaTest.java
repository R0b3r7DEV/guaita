package dev.r0b3r7.guaita.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Función pura de percentil: interpolación, saturación y el caso de breakpoints repetidos. */
class ClimatologiaTest {

  /** Referencia recta: P0=0, P1=1, ..., P100=100 (breakpoints[i] == i). */
  private static double[] recta() {
    double[] bp = new double[101];
    for (int i = 0; i <= 100; i++) {
      bp[i] = i;
    }
    return bp;
  }

  @Test
  void valorExactamenteEnUnBreakpoint() {
    assertEquals(50.0, Climatologia.percentil(50.0, recta()), 1e-9);
    assertEquals(0.0, Climatologia.percentil(0.0, recta()), 1e-9); // == P0
  }

  @Test
  void valorEntreDosBreakpointsInterpola() {
    assertEquals(50.5, Climatologia.percentil(50.5, recta()), 1e-9);
    assertEquals(87.25, Climatologia.percentil(87.25, recta()), 1e-9);
  }

  @Test
  void pordebajoDeP0EsCero() {
    assertEquals(0.0, Climatologia.percentil(-3.0, recta()), 1e-9);
  }

  @Test
  void porEncimaDeP100Satura() {
    // Un récord (día 2026 vs referencia 2025) debe salir 100, nunca >100.
    assertEquals(100.0, Climatologia.percentil(9999.0, recta()), 1e-9);
    assertEquals(100.0, Climatologia.percentil(100.0, recta()), 1e-9); // == P100
  }

  @Test
  void breakpointsRepetidosNoDividenPorCeroNiNaN() {
    // Invierno: P0..P40 todos 0; luego sube 1..60.
    double[] bp = new double[101];
    for (int i = 0; i <= 40; i++) {
      bp[i] = 0.0;
    }
    for (int i = 41; i <= 100; i++) {
      bp[i] = i - 40; // P41=1 ... P100=60
    }
    double p = Climatologia.percentil(0.0, bp);
    assertFalse(Double.isNaN(p), "no puede ser NaN con meseta de ceros");
    assertEquals(40.0, p, 1e-9, "borde superior de la meseta de ceros = P40");
    // dentro del tramo que sube
    assertEquals(40.5, Climatologia.percentil(0.5, bp), 1e-9);
    // y una meseta en medio no rompe
    assertTrue(Climatologia.percentil(1.0, bp) >= 41.0);
  }
}
