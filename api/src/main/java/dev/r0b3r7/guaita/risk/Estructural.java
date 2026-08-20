package dev.r0b3r7.guaita.risk;

import java.util.Map;

/**
 * Parte ESTÁTICA del componente estructural (docs/04 §2): combina fracción forestal, continuidad,
 * peso de combustible y pendiente. f_tiempo NO va aquí (es dinámico, depende de la fecha; se aplica
 * al construir el índice). Puro: PostGIS calcula las áreas, Java combina.
 */
public final class Estructural {

  private Estructural() {}

  /** f_pendiente = min(1, pendiente_p90/100 · 2). Se satura pronto: pendientes p90 ≥ 50 % dan 1. */
  public static double fPendiente(double pendienteP90Pct) {
    return Math.min(1.0, pendienteP90Pct / 100.0 * 2.0);
  }

  /** Peso de combustible ponderado por área y fracción forestal sin dato de combustible. */
  public record PesoResultado(double peso, double fracSinCombustible) {}

  /**
   * Peso medio ponderado por área sobre la superficie forestal del municipio. La fracción cubierta
   * usa el peso de su modelo Anderson; el hueco (sin dato de combustible) usa {@code pesoDefecto}.
   *
   * @param forestalArea superficie forestal del municipio (misma unidad que las áreas por código)
   * @param areaPorCodigo área forestal cubierta por cada código Anderson (partición, sin solape)
   */
  public static PesoResultado pesoPonderado(
      double forestalArea,
      Map<String, Double> areaPorCodigo,
      Map<String, Double> pesos,
      double pesoDefecto) {
    if (forestalArea <= 0) {
      return new PesoResultado(0.0, 0.0); // municipio sin monte
    }
    double cubierta = 0.0;
    double ponderado = 0.0;
    for (Map.Entry<String, Double> e : areaPorCodigo.entrySet()) {
      double a = e.getValue();
      double peso = pesos.getOrDefault(e.getKey(), pesoDefecto); // código desconocido -> defecto
      cubierta += a;
      ponderado += a * peso;
    }
    // El overlay puede rozar el borde forestal; que el hueco nunca sea negativo.
    cubierta = Math.min(cubierta, forestalArea);
    double huecoArea = Math.max(0.0, forestalArea - cubierta);
    double peso = (ponderado + huecoArea * pesoDefecto) / forestalArea;
    peso = Math.min(1.0, Math.max(0.0, peso)); // defensivo ante solapes de la cartografía
    return new PesoResultado(peso, huecoArea / forestalArea);
  }

  /**
   * Parte estática (0..100): {@code 100 · frac_forestal^0.5 · (0.5 + 0.5·continuidad) · peso ·
   * (0.6 + 0.4·f_pendiente)}. La raíz de frac_forestal evita descartar un municipio con 30 %
   * forestal (30 % de un término grande sigue siendo mucho monte).
   */
  public static double parteEstatica(
      double fracForestal, double continuidad, double peso, double fPendiente) {
    return 100.0
        * Math.sqrt(fracForestal)
        * (0.5 + 0.5 * continuidad)
        * peso
        * (0.6 + 0.4 * fPendiente);
  }
}
