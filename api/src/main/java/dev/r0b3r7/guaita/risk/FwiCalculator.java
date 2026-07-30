package dev.r0b3r7.guaita.risk;

/**
 * Sistema canadiense Fire Weather Index (FWI). Implementado DESDE LAS ECUACIONES PUBLICADAS en: Van
 * Wagner, C.E. &amp; Pickett, T.L. (1985), «Equations and FORTRAN program for the Canadian Forest
 * Fire Weather Index System», Forestry Technical Report 33. (El informe de estructura de 1987
 * —Report 35, referencia de doc 04— describe el sistema; las ecuaciones exactas y su tabla de
 * ejemplo están en el 33. Números de ecuación citados abajo.)
 *
 * <p>Función PURA: sin red, sin BD, sin Spring. {@link #step} recibe el estado de ayer
 * (FFMC/DMC/DC), la meteo del día y el mes; devuelve los seis códigos de hoy. Los tres códigos de
 * humedad son RECURSIVOS: no se calcula un día suelto sin la cadena previa (doc 04 §1).
 *
 * <p><b>Longitud de día y latitud (doc 04 §1).</b> Los factores Le (DMC) y Lf (DC) del informe
 * están tabulados para latitudes canadienses. Se dejan CONFIGURABLES en el constructor. {@link
 * #canada()} usa los canadienses estándar, que reproducen la tabla de ejemplo de la publicación (el
 * test de referencia). Para Castellón (~40°N) la adaptación aplica el ajuste de Lawson &amp;
 * Armitage (2008) —el de cffdrs/EFFIS—; queda como RIESGO ABIERTO en docs/04. Por defecto, los
 * canadienses, dicho explícitamente, para no meter constantes sin procedencia.
 */
public final class FwiCalculator {

  /** Valores de arranque tras periodo húmedo (doc 04 §1; cabecera del ejemplo del informe). */
  public static final double STARTUP_FFMC = 85.0;

  public static final double STARTUP_DMC = 6.0;
  public static final double STARTUP_DC = 15.0;

  // Rango de entrada plausible: fuera de esto es error de datos y se rechaza, no se calcula (doc 04
  // §1: un viento en m/s en vez de km/h daría un FWI bajo "sin que nadie se entere").
  private static final double TEMP_MIN = -50.0;
  private static final double TEMP_MAX = 60.0;

  // Tabla 1 (Le, longitud efectiva de día para DMC) y Tabla 2 (Lf, factor para DC), Ene..Dic,
  // valores canadienses estándar del informe (Van Wagner & Pickett 1985, Tablas 1 y 2). Lf de
  // Ene-Mar (-1.6) se leyó directo del informe; el resto se valida reproduciendo las columnas
  // DMC/DC de su tabla de ejemplo (test de referencia).
  private static final double[] LE_CANADA = {
    6.5, 7.5, 9.0, 12.8, 13.9, 13.9, 12.4, 10.9, 9.4, 8.0, 7.0, 6.0
  };
  private static final double[] LF_CANADA = {
    -1.6, -1.6, -1.6, 0.9, 3.8, 5.8, 6.4, 5.0, 2.4, 0.4, -1.6, -1.6
  };

  private final double[] le;
  private final double[] lf;

  public FwiCalculator(double[] leByMonth, double[] lfByMonth) {
    if (leByMonth.length != 12 || lfByMonth.length != 12) {
      throw new IllegalArgumentException("Le y Lf deben tener 12 valores (Ene..Dic)");
    }
    this.le = leByMonth.clone();
    this.lf = lfByMonth.clone();
  }

  /** Calculadora con los factores de longitud de día canadienses estándar (Van Wagner 1987). */
  public static FwiCalculator canada() {
    return new FwiCalculator(LE_CANADA, LF_CANADA);
  }

  /**
   * Calcula los códigos FWI de un día a partir del estado de ayer. {@code month} en 1..12. Rechaza
   * meteo imposible (HR fuera de 0..100, temperatura fuera de rango, viento o lluvia negativos).
   */
  public FwiCodes step(double prevFfmc, double prevDmc, double prevDc, int month, FwiWeather w) {
    if (month < 1 || month > 12) {
      throw new IllegalArgumentException("mes fuera de 1..12: " + month);
    }
    validar(w);
    double ffmc = ffmc(prevFfmc, w);
    double dmc = dmc(prevDmc, w, le[month - 1]);
    double dc = dc(prevDc, w, lf[month - 1]);
    double isi = isi(ffmc, w.windKmh());
    double bui = bui(dmc, dc);
    double fwi = fwi(isi, bui);
    return new FwiCodes(ffmc, dmc, dc, isi, bui, fwi);
  }

  private static void validar(FwiWeather w) {
    if (!(w.rhPct() >= 0.0 && w.rhPct() <= 100.0)) {
      throw new IllegalArgumentException("HR fuera de 0..100: " + w.rhPct());
    }
    if (!(w.tempC() >= TEMP_MIN && w.tempC() <= TEMP_MAX)) {
      throw new IllegalArgumentException("temperatura fuera de rango [-50,60]: " + w.tempC());
    }
    if (!(w.windKmh() >= 0.0)) {
      throw new IllegalArgumentException("viento negativo (¿unidades?): " + w.windKmh());
    }
    if (!(w.rainMm() >= 0.0)) {
      throw new IllegalArgumentException("lluvia negativa: " + w.rainMm());
    }
  }

  // ---- Fine Fuel Moisture Code, ecuaciones 1-10 ----
  private static double ffmc(double prevF, FwiWeather w) {
    double t = w.tempC();
    double h = w.rhPct();
    double wind = w.windKmh();
    double ro = w.rainMm();
    double mo = 147.2 * (101.0 - prevF) / (59.5 + prevF); // (1)
    if (ro > 0.5) { // la rutina de lluvia se omite en tiempo seco (ro<=0.5)
      double rf = ro - 0.5; // (2)
      double mr =
          mo + 42.5 * rf * Math.exp(-100.0 / (251.0 - mo)) * (1.0 - Math.exp(-6.93 / rf)); // (3a)
      if (mo > 150.0) {
        mr += 0.0015 * (mo - 150.0) * (mo - 150.0) * Math.sqrt(rf); // (3b)
      }
      mo = Math.min(mr, 250.0); // m tiene un límite superior de 250
    }
    double ed =
        0.942 * Math.pow(h, 0.679)
            + 11.0 * Math.exp((h - 100.0) / 10.0)
            + 0.18 * (21.1 - t) * (1.0 - Math.exp(-0.115 * h)); // (4)
    double m;
    if (mo > ed) {
      double ko =
          0.424 * (1.0 - Math.pow(h / 100.0, 1.7))
              + 0.0694 * Math.sqrt(wind) * (1.0 - Math.pow(h / 100.0, 8.0)); // (6a)
      double kd = ko * 0.581 * Math.exp(0.0365 * t); // (6b)
      m = ed + (mo - ed) * Math.pow(10.0, -kd); // (8)
    } else {
      double ew =
          0.618 * Math.pow(h, 0.753)
              + 10.0 * Math.exp((h - 100.0) / 10.0)
              + 0.18 * (21.1 - t) * (1.0 - Math.exp(-0.115 * h)); // (5)
      if (mo < ew) {
        double hh = (100.0 - h) / 100.0; // HUMECTACIÓN: (100-H)/100, no H/100 (ec. 7a)
        double k1 =
            0.424 * (1.0 - Math.pow(hh, 1.7))
                + 0.0694 * Math.sqrt(wind) * (1.0 - Math.pow(hh, 8.0)); // (7a)
        double kw = k1 * 0.581 * Math.exp(0.0365 * t); // (7b)
        m = ew - (ew - mo) * Math.pow(10.0, -kw); // (9)
      } else {
        m = mo; // Ed >= mo >= Ew
      }
    }
    return 59.5 * (250.0 - m) / (147.2 + m); // (10)
  }

  // ---- Duff Moisture Code, ecuaciones 11-17 ----
  private static double dmc(double prevP, FwiWeather w, double leMonth) {
    double t = Math.max(w.tempC(), -1.1); // restricción: T < -1.1 no se usa en la ec. 16
    double h = w.rhPct();
    double ro = w.rainMm();
    double po = prevP;
    if (ro > 1.5) { // rutina de lluvia solo si ro>1.5
      double re = 0.92 * ro - 1.27; // (11)
      double m0 = 20.0 + Math.exp(5.6348 - po / 43.43); // (12)
      double b;
      if (po <= 33.0) {
        b = 100.0 / (0.5 + 0.3 * po); // (13a)
      } else if (po <= 65.0) {
        b = 14.0 - 1.3 * Math.log(po); // (13b)
      } else {
        b = 6.2 * Math.log(po) - 17.2; // (13c)
      }
      double mr = m0 + 1000.0 * re / (48.77 + b * re); // (14)
      double pr = 244.72 - 43.43 * Math.log(mr - 20.0); // (15)
      po = Math.max(pr, 0.0); // Pr no puede ser < 0
    }
    double k = 1.894 * (t + 1.1) * (100.0 - h) * leMonth * 1.0e-6; // (16)
    return po + 100.0 * k; // (17)
  }

  // ---- Drought Code, ecuaciones 18-23 ----
  private static double dc(double prevD, FwiWeather w, double lfMonth) {
    double t = Math.max(w.tempC(), -2.8); // restricción: T < -2.8 no se usa en la ec. 22
    double ro = w.rainMm();
    double dPrev = prevD;
    if (ro > 2.8) { // rutina de lluvia solo si ro>2.8
      double rd = 0.83 * ro - 1.27; // (18)
      double qo = 800.0 * Math.exp(-dPrev / 400.0); // (19)
      double qr = qo + 3.937 * rd; // (20)
      double dr = 400.0 * Math.log(800.0 / qr); // (21)
      dPrev = Math.max(dr, 0.0); // Dr no puede ser < 0
    }
    double v = 0.36 * (t + 2.8) + lfMonth; // (22)
    if (v < 0.0) {
      v = 0.0; // V no puede ser negativo
    }
    return dPrev + 0.5 * v; // (23)
  }

  // ---- Initial Spread Index, ecuaciones 24-26 ----
  private static double isi(double ffmc, double wind) {
    double m = 147.2 * (101.0 - ffmc) / (59.5 + ffmc); // humedad de la hojarasca desde el FFMC
    double fWind = Math.exp(0.05039 * wind); // (24)
    double fFuel = 91.9 * Math.exp(-0.1386 * m) * (1.0 + Math.pow(m, 5.31) / 4.93e7); // (25)
    return 0.208 * fWind * fFuel; // (26)
  }

  // ---- Buildup Index, ecuación 27 ----
  private static double bui(double dmc, double dc) {
    if (dmc <= 0.0) {
      return 0.0;
    }
    double u;
    if (dmc <= 0.4 * dc) {
      u = 0.8 * dmc * dc / (dmc + 0.4 * dc); // (27a)
    } else {
      double frac = 0.8 * dc / (dmc + 0.4 * dc);
      u = dmc - (1.0 - frac) * (0.92 + Math.pow(0.0114 * dmc, 1.7)); // (27b)
    }
    return Math.max(u, 0.0);
  }

  // ---- Fire Weather Index, ecuaciones 28-30 ----
  private static double fwi(double isi, double bui) {
    double fD =
        bui <= 80.0
            ? 0.626 * Math.pow(bui, 0.809) + 2.0 // (28a)
            : 1000.0 / (25.0 + 108.64 * Math.exp(-0.023 * bui)); // (28b)
    double b = 0.1 * isi * fD; // (29)
    return b > 1.0 ? Math.exp(2.72 * Math.pow(0.434 * Math.log(b), 0.647)) : b; // (30a/30b)
  }
}
