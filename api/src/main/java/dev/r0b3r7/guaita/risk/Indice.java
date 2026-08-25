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
   * Índice v1.1 (docs/09): {@code comp_meteo_abs · modulador(comp_estructural)}. La meteo (percentil
   * provincial, absoluta) es la base; la estructura MODULA en una banda acotada, no multiplica en
   * rango completo (que diluía la señal meteo, AUC 0,891→0,767). El modulador es lineal, {@code
   * clip(1 + pendiente·(ce − anclaje), min, max)}, con la pendiente DERIVADA del efecto sobre el
   * tamaño (Spearman 0,616; extremo prudente del IC), no ajustada contra la ignición. comp_vulnerab
   * NO entra: mide exposición ("qué se pierde"), no peligro, y va aparte como contexto.
   */
  public static double calcularV11(
      double compMeteoAbs, double compEstructural, ModeloParams.Indice cfg) {
    double mod =
        Math.max(
            cfg.moduladorMin(),
            Math.min(
                cfg.moduladorMax(),
                1.0 + cfg.moduladorPendiente() * (compEstructural - cfg.moduladorAnclaje())));
    double idx = Math.max(0.0, compMeteoAbs) * mod;
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
