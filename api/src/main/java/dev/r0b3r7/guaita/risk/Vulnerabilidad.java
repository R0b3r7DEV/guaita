package dev.r0b3r7.guaita.risk;

/**
 * Componente de vulnerabilidad PROVISIONAL (docs/04 §3). La versión cerrada del documento pondera
 * edificaciones en interfaz, población en franja de 500 m y vías de evacuación, todo del módulo WUI
 * (doc 05) que aún no existe. Hasta entonces se usa un proxy más pobre: población municipal
 * normalizada + fracción de suelo protegido. Puro: PostGIS calcula las áreas, Java combina.
 *
 * <p>La población en bruto es un proxy DÉBIL de exposición —mide gente en el casco urbano, que
 * normalmente no arde— y se sustituye en v2.0 por los datos reales del módulo IUF/WUI.
 */
public final class Vulnerabilidad {

  private Vulnerabilidad() {}

  /** Transformación de la población normalizada por el máximo provincial (docs/04 §3). */
  public enum NormaPoblacion {
    /** pob/max. Casi binaria: solo la capital puntúa; no discrimina el 90% de la provincia. */
    LINEAL,
    /** sqrt(pob/max). Punto medio con lectura física (daño ~ superficie urbanizada, sublineal). */
    SQRT,
    /** log1p(pob)/log1p(max). Comprime de más: acerca un pueblo de 200 hab a uno de 2.000. */
    LOG
  }

  /**
   * Población normalizada a 0..1 sobre el máximo provincial, con la transformación elegida. Mide
   * exposición ABSOLUTA (más gente = más exposición), no per cápita. La transformación mejora la
   * DISCRIMINACIÓN entre municipios, no la validez del proxy (sigue midiendo casco urbano; docs/04
   * §3).
   */
  public static double poblacionNorm(int poblacion, int poblacionMax, NormaPoblacion norma) {
    if (poblacionMax <= 0 || poblacion <= 0) {
      return 0.0;
    }
    double lineal = Math.min(1.0, (double) poblacion / poblacionMax);
    return switch (norma) {
      case LINEAL -> lineal;
      case SQRT -> Math.sqrt(lineal);
      case LOG -> Math.log1p(poblacion) / Math.log1p(poblacionMax);
    };
  }

  /**
   * comp_vulnerab provisional (0..100) = {@code 100 · (pesoPob · poblacionNorm + pesoProt ·
   * fracProtegido)}. Los pesos (0.70 / 0.30) suman 1 y las entradas van en [0,1], así que el
   * resultado ya cae en [0,100]; se acota por defensa ante cartografía con solapes.
   */
  public static double compVulnerab(
      double poblacionNorm, double fracProtegido, ModeloParams.Vulnerab cfg) {
    double v = cfg.pesoPoblacion() * poblacionNorm + cfg.pesoEspacioProtegido() * fracProtegido;
    return 100.0 * Math.min(1.0, Math.max(0.0, v));
  }
}
