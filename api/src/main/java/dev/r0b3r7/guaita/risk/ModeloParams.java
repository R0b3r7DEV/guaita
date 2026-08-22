package dev.r0b3r7.guaita.risk;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parámetros del índice de peligro, cargados de {@code model-params.yml} (docs/04). Es la fuente de
 * verdad de la reproducibilidad: cambiar cualquier valor obliga a subir {@link #version} y
 * recalcular todo el histórico (docs/04 §5). Inmutable (record) para que nadie lo mute en caliente.
 */
@ConfigurationProperties(prefix = "guaita.modelo")
public record ModeloParams(
    String version,
    Meteo meteo,
    Combustible combustible,
    FTiempo fTiempo,
    Vulnerab vulnerab,
    Indice indice) {

  /** Normalización meteo por ventana estacional; periodo base CONGELADO (docs/04 §1). */
  public record Meteo(int ventanaDias, LocalDate baseDesde, LocalDate baseHasta) {}

  /** Pesos de inflamabilidad Anderson (código→peso 0..1) y peso fijo del hueco sin dato. */
  public record Combustible(Map<String, Double> pesos, double pesoDefecto) {}

  /** Curva en U de f_tiempo (años desde el último incendio→factor 0..1; docs/04 §2.4). */
  public record FTiempo(
      int jovenHasta,
      double jovenValor,
      int picoDesde,
      int picoHasta,
      double picoValor,
      int viejoDesde,
      double viejoValor,
      double sinDatoValor,
      double repartoMinFracForestal) {}

  /** Pesos y normalización de la vulnerabilidad provisional v1.0 (sin IUF; docs/04 §3). */
  public record Vulnerab(
      double pesoPoblacion,
      double pesoEspacioProtegido,
      Vulnerabilidad.NormaPoblacion normaPoblacion) {}

  /** Combinación del índice y umbrales de nivel (docs/04 §4). */
  public record Indice(double pesoEstructural, double pesoVulnerab, List<Integer> niveles) {}
}
