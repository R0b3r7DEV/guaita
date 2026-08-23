package dev.r0b3r7.guaita.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Núcleo del arnés de backtest, con datos sintéticos de resultado conocido (sin BD). */
class BacktestTest {

  @Test
  void aucRocCasosLimite() {
    // separación perfecta: positivos por encima -> 1.0
    assertEquals(
        1.0,
        Backtest.aucRoc(new double[] {1, 2, 3, 4}, new boolean[] {false, false, true, true}),
        1e-9);
    // orden inverso -> 0.0
    assertEquals(
        0.0,
        Backtest.aucRoc(new double[] {1, 2, 3, 4}, new boolean[] {true, true, false, false}),
        1e-9);
    // todo empatado -> 0.5 (el rango medio reparte medio acierto)
    assertEquals(
        0.5,
        Backtest.aucRoc(new double[] {5, 5, 5, 5}, new boolean[] {true, true, false, false}),
        1e-9);
  }

  @Test
  void aucRocValorConocido() {
    // positivos en score 2 y 4; negativos en 1 y 3 -> pares ganados 3 de 4 = 0.75
    assertEquals(
        0.75,
        Backtest.aucRoc(new double[] {1, 2, 3, 4}, new boolean[] {false, true, false, true}),
        1e-9);
  }

  @Test
  void aucRocExigePositivoYNegativo() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Backtest.aucRoc(new double[] {1, 2}, new boolean[] {true, true}));
  }

  @Test
  void aucPrValorConocido() {
    // desc: T(prec1,rec.5), F, T(prec2/3,rec1), F -> AP = 0.5*1 + 0.5*(2/3) = 0.8333…
    double ap =
        Backtest.aucPr(new double[] {0.9, 0.8, 0.7, 0.6}, new boolean[] {true, false, true, false});
    assertEquals(0.5 + 0.5 * (2.0 / 3.0), ap, 1e-9);
  }

  @Test
  void sensibilidadYFalsaAlarma() {
    double[] s = {10, 40, 55, 62, 80};
    boolean[] p = {false, false, true, false, true}; // positivos en 55 y 80
    // umbral 60 (nivel 4): marca 62 y 80; detecta 1 de 2 positivos (80)
    assertEquals(0.5, Backtest.sensibilidad(s, p, 60), 1e-9);
    // marcados {62,80}; falso = 62 -> 1/2
    assertEquals(0.5, Backtest.tasaFalsaAlarma(s, p, 60), 1e-9);
    // nadie marcado por encima de 100 -> 0, sin dividir por cero
    assertEquals(0.0, Backtest.tasaFalsaAlarma(s, p, 100), 1e-9);
  }

  @Test
  void bootstrapEnvuelveElPuntualYEsDeterminista() {
    // 8 positivos claros + 8 negativos claros
    double[] s = new double[16];
    boolean[] p = new boolean[16];
    for (int i = 0; i < 8; i++) {
      s[i] = 10 + i;
      p[i] = false;
    }
    for (int i = 8; i < 16; i++) {
      s[i] = 60 + i;
      p[i] = true;
    }
    double puntual = Backtest.aucRoc(s, p);
    double[] ic = Backtest.bootstrapAucRocIc(s, p, 500, 42L, 0.05);
    assertTrue(ic[0] <= puntual && puntual <= ic[1], "el IC debe envolver el AUC puntual");
    assertTrue(ic[0] >= 0.0 && ic[1] <= 1.0, "el AUC vive en [0,1]");
    // determinista con la misma semilla
    double[] ic2 = Backtest.bootstrapAucRocIc(s, p, 500, 42L, 0.05);
    assertEquals(ic[0], ic2[0], 1e-12);
    assertEquals(ic[1], ic2[1], 1e-12);
  }

  @Test
  void bootstrapConPocosPositivosDaIcAncho() {
    // 3 positivos, muchos negativos: el IC debe salir ANCHO (la señal de docs/09)
    int n = 200;
    double[] s = new double[n];
    boolean[] p = new boolean[n];
    java.util.Random r = new java.util.Random(1);
    for (int i = 0; i < n; i++) {
      s[i] = r.nextDouble() * 50;
    }
    // 3 positivos SOLAPADOS con los negativos (0..50): así el bootstrap sobre solo
    // 3 casos varía mucho y el IC sale ancho — la señal de que no se puede calibrar.
    double[] vals = {10, 30, 49};
    for (int k = 0; k < 3; k++) {
      s[k] = vals[k];
      p[k] = true;
    }
    double[] ic = Backtest.bootstrapAucRocIc(s, p, 800, 7L, 0.05);
    assertTrue(ic[1] - ic[0] > 0.10, "con 3 positivos el IC del AUC es ancho: " + (ic[1] - ic[0]));
  }
}
