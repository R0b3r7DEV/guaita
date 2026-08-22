package dev.r0b3r7.guaita.risk;

import java.util.List;

/**
 * Índice de peligro compuesto (docs/04 §4). Combina los tres componentes 0..100 con una MEDIA
 * GEOMÉTRICA, no aritmética, y es deliberado: si {@code comp_meteo} es cero (día lluvioso) el
 * índice debe irse a cero por mucho combustible que haya. Una suma ponderada daría falsos positivos
 * todo el invierno. Puro y sin estado.
 */
public final class Indice {

  private Indice() {}

  /**
   * {@code indice = comp_meteo^0.5 · (peso_estructural·comp_estructural +
   * peso_vulnerab·comp_vulnerab)^0.5}. Los tres componentes van en 0..100 y los pesos suman 1, así
   * que el resultado cae en 0..100. La meteo actúa de compuerta: comp_meteo=0 → índice 0.
   */
  public static double calcular(
      double compMeteo, double compEstructural, double compVulnerab, ModeloParams.Indice cfg) {
    double combustibilidad =
        cfg.pesoEstructural() * compEstructural + cfg.pesoVulnerab() * compVulnerab;
    double idx = Math.sqrt(Math.max(0.0, compMeteo)) * Math.sqrt(Math.max(0.0, combustibilidad));
    return Math.min(100.0, Math.max(0.0, idx));
  }

  /**
   * Nivel 1..5 según los límites SUPERIORES de {@code niveles} (config, p.ej. [20,40,60,80,100]):
   * el primer tramo cuyo tope no supera el índice. Un índice justo en el tope cae en ese tramo.
   */
  public static int nivel(double indice, List<Integer> niveles) {
    for (int i = 0; i < niveles.size(); i++) {
      if (indice <= niveles.get(i)) {
        return i + 1;
      }
    }
    return niveles.size();
  }
}
