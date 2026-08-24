package dev.r0b3r7.guaita.risk;

import java.util.Arrays;
import java.util.Random;

/**
 * Métricas del backtest (docs/09), PURAS y sin estado: reciben puntuaciones + etiquetas y devuelven
 * números. La orquestación (leer indice_peligro, cruzar positivos EGIF, partición temporal, líneas
 * base y ablación) se monta encima cuando lleguen los positivos; esto es el núcleo que ya se puede
 * escribir y testear con datos sintéticos.
 *
 * <p>Con desbalance extremo (docenas de positivos frente a cientos de miles de negativos) la
 * accuracy es inútil; se usan AUC-ROC, AUC-PR (la honesta con desbalance) y sensibilidad/falsa
 * alarma a un umbral, siempre con IC por bootstrap, nunca un número pelado (docs/09).
 */
public final class Backtest {

  private Backtest() {}

  /**
   * AUC-ROC por rangos (equivalente a Mann-Whitney U): probabilidad de que un positivo puntúe por
   * encima de un negativo. Empates resueltos con rango medio → 0.5 aporta medio acierto.
   */
  public static double aucRoc(double[] score, boolean[] positivo) {
    int n = score.length;
    if (positivo.length != n) {
      throw new IllegalArgumentException("score y positivo con distinta longitud");
    }
    Integer[] orden = ordenAscendente(score);
    double[] rango = new double[n];
    int i = 0;
    while (i < n) {
      int j = i;
      while (j < n && score[orden[j]] == score[orden[i]]) {
        j++;
      }
      double r = (i + 1 + j) / 2.0; // rango medio del bloque de empatados (rangos 1..n)
      for (int k = i; k < j; k++) {
        rango[orden[k]] = r;
      }
      i = j;
    }
    double sumaPos = 0;
    long nPos = 0;
    long nNeg = 0;
    for (int k = 0; k < n; k++) {
      if (positivo[k]) {
        sumaPos += rango[k];
        nPos++;
      } else {
        nNeg++;
      }
    }
    if (nPos == 0 || nNeg == 0) {
      throw new IllegalArgumentException("el AUC necesita al menos un positivo y un negativo");
    }
    return (sumaPos - nPos * (nPos + 1) / 2.0) / ((double) nPos * nNeg);
  }

  /**
   * AUC-PR como precisión media (average precision): área bajo la curva precisión-recall
   * recorriendo las puntuaciones de mayor a menor. La referencia "aleatoria" es la prevalencia
   * (nPos/n), no 0.5.
   */
  public static double aucPr(double[] score, boolean[] positivo) {
    int n = score.length;
    Integer[] orden = ordenDescendente(score);
    long nPos = 0;
    for (boolean p : positivo) {
      if (p) {
        nPos++;
      }
    }
    if (nPos == 0) {
      throw new IllegalArgumentException("el AUC-PR necesita al menos un positivo");
    }
    double tp = 0;
    double fp = 0;
    double ap = 0;
    double recallPrev = 0;
    for (int k = 0; k < n; k++) {
      if (positivo[orden[k]]) {
        tp++;
      } else {
        fp++;
      }
      double recall = tp / nPos;
      if (recall > recallPrev) {
        double precision = tp / (tp + fp);
        ap += (recall - recallPrev) * precision;
        recallPrev = recall;
      }
    }
    return ap;
  }

  /** Sensibilidad a un umbral: fracción de positivos con puntuación ≥ umbral (recall). */
  public static double sensibilidad(double[] score, boolean[] positivo, double umbral) {
    long nPos = 0;
    long detectados = 0;
    for (int k = 0; k < score.length; k++) {
      if (positivo[k]) {
        nPos++;
        if (score[k] >= umbral) {
          detectados++;
        }
      }
    }
    return nPos == 0 ? 0.0 : (double) detectados / nPos;
  }

  /**
   * Tasa de falsa alarma a un umbral: días marcados sin incendio / total de marcados (docs/09). Se
   * reporta, no se optimiza a ciegas: los falsos positivos son baratos y los negativos, caros.
   */
  public static double tasaFalsaAlarma(double[] score, boolean[] positivo, double umbral) {
    long marcados = 0;
    long falsos = 0;
    for (int k = 0; k < score.length; k++) {
      if (score[k] >= umbral) {
        marcados++;
        if (!positivo[k]) {
          falsos++;
        }
      }
    }
    return marcados == 0 ? 0.0 : (double) falsos / marcados;
  }

  /**
   * IC del AUC-ROC por bootstrap (remuestreo de casos con reemplazo). Devuelve {@code [lo, hi]} a
   * (1-alpha). Con pocos positivos el intervalo sale ANCHO a propósito: es la señal de que no se
   * puede calibrar todavía (docs/09). Las réplicas degeneradas (sin positivos o sin negativos) se
   * descartan.
   */
  public static double[] bootstrapAucRocIc(
      double[] score, boolean[] positivo, int nBoot, long semilla, double alpha) {
    int n = score.length;
    Random rnd = new Random(semilla);
    double[] aucs = new double[nBoot];
    int m = 0;
    for (int b = 0; b < nBoot; b++) {
      double[] s = new double[n];
      boolean[] p = new boolean[n];
      boolean hayPos = false;
      boolean hayNeg = false;
      for (int k = 0; k < n; k++) {
        int idx = rnd.nextInt(n);
        s[k] = score[idx];
        p[k] = positivo[idx];
        hayPos |= p[k];
        hayNeg |= !p[k];
      }
      if (hayPos && hayNeg) {
        aucs[m++] = aucRoc(s, p);
      }
    }
    if (m == 0) {
      throw new IllegalStateException("bootstrap sin réplicas válidas (muy pocos positivos)");
    }
    double[] validas = Arrays.copyOf(aucs, m);
    Arrays.sort(validas);
    return new double[] {percentil(validas, alpha / 2), percentil(validas, 1 - alpha / 2)};
  }

  /**
   * AUC-ROC a partir de arrays SEPARADOS de positivos y negativos (equivale a {@link #aucRoc} pero
   * ordena solo los negativos una vez; rápido cuando hay cientos de miles de negativos). Empates:
   * medio acierto.
   */
  public static double aucRocSep(double[] pos, double[] neg) {
    if (pos.length == 0 || neg.length == 0) {
      throw new IllegalArgumentException("el AUC necesita positivos y negativos");
    }
    double[] n = neg.clone();
    Arrays.sort(n);
    double suma = 0;
    for (double p : pos) {
      suma += colocacion(p, n);
    }
    return suma / pos.length;
  }

  /**
   * AUC-ROC puntual + IC por bootstrap de los VALORES DE COLOCACIÓN de los positivos (docs/09): con
   * pocos positivos y muchos negativos, la incertidumbre la dominan los positivos, así que se
   * remuestrean ellos (los negativos, fijos). Devuelve {@code [auc, lo, hi]} a (1-alpha).
   */
  public static double[] aucRocIc(
      double[] pos, double[] neg, int nBoot, long semilla, double alpha) {
    if (pos.length == 0 || neg.length == 0) {
      throw new IllegalArgumentException("el AUC necesita positivos y negativos");
    }
    double[] n = neg.clone();
    Arrays.sort(n);
    double[] u = new double[pos.length];
    double suma = 0;
    for (int i = 0; i < pos.length; i++) {
      u[i] = colocacion(pos[i], n);
      suma += u[i];
    }
    double auc = suma / u.length;
    Random rnd = new Random(semilla);
    double[] rep = new double[nBoot];
    for (int b = 0; b < nBoot; b++) {
      double s = 0;
      for (int i = 0; i < u.length; i++) {
        s += u[rnd.nextInt(u.length)];
      }
      rep[b] = s / u.length;
    }
    Arrays.sort(rep);
    return new double[] {auc, percentil(rep, alpha / 2), percentil(rep, 1 - alpha / 2)};
  }

  // Valor de colocación de un positivo: (#neg < p + 0.5·#neg = p) / nNeg, con búsqueda binaria.
  private static double colocacion(double p, double[] negOrdenado) {
    int menores = limiteInferior(negOrdenado, p);
    int iguales = limiteSuperior(negOrdenado, p) - menores;
    return (menores + 0.5 * iguales) / negOrdenado.length;
  }

  private static int limiteInferior(double[] a, double x) {
    int lo = 0;
    int hi = a.length;
    while (lo < hi) {
      int m = (lo + hi) >>> 1;
      if (a[m] < x) {
        lo = m + 1;
      } else {
        hi = m;
      }
    }
    return lo;
  }

  private static int limiteSuperior(double[] a, double x) {
    int lo = 0;
    int hi = a.length;
    while (lo < hi) {
      int m = (lo + hi) >>> 1;
      if (a[m] <= x) {
        lo = m + 1;
      } else {
        hi = m;
      }
    }
    return lo;
  }

  private static double percentil(double[] ordenado, double q) {
    if (ordenado.length == 1) {
      return ordenado[0];
    }
    double pos = q * (ordenado.length - 1);
    int lo = (int) Math.floor(pos);
    int hi = (int) Math.ceil(pos);
    return ordenado[lo] + (pos - lo) * (ordenado[hi] - ordenado[lo]);
  }

  private static Integer[] ordenAscendente(double[] v) {
    Integer[] idx = new Integer[v.length];
    for (int k = 0; k < v.length; k++) {
      idx[k] = k;
    }
    Arrays.sort(idx, (a, b) -> Double.compare(v[a], v[b]));
    return idx;
  }

  private static Integer[] ordenDescendente(double[] v) {
    Integer[] idx = ordenAscendente(v);
    for (int a = 0, b = idx.length - 1; a < b; a++, b--) {
      Integer t = idx[a];
      idx[a] = idx[b];
      idx[b] = t;
    }
    return idx;
  }
}
