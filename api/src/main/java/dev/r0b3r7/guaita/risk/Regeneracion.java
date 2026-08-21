package dev.r0b3r7.guaita.risk;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.OptionalInt;

/**
 * Factor de regeneración f_tiempo: la curva en U del combustible según los años desde el último
 * incendio (docs/04 §2.4). Bajo justo tras el fuego (no hay combustible), MÁXIMO a los ~8-15 años
 * (regenerado denso, fino, con mucho muerto en pie), y alto de nuevo por acumulación pasados ~25.
 * Puro y sin estado; los parámetros y el umbral de reparto vienen de {@link ModeloParams.FTiempo}.
 */
public final class Regeneracion {

  private Regeneracion() {}

  /**
   * Factor 0..1 dados los años desde el incendio. Constante en los tramos plano-joven, meseta-pico
   * y viejo; interpolación lineal en la subida (jovenHasta..picoDesde) y la bajada
   * (picoHasta..viejoDesde).
   */
  public static double fTiempo(int anios, ModeloParams.FTiempo c) {
    if (anios <= c.jovenHasta()) {
      return c.jovenValor();
    }
    if (anios <= c.picoDesde()) {
      return interp(anios, c.jovenHasta(), c.picoDesde(), c.jovenValor(), c.picoValor());
    }
    if (anios <= c.picoHasta()) {
      return c.picoValor();
    }
    if (anios <= c.viejoDesde()) {
      return interp(anios, c.picoHasta(), c.viejoDesde(), c.picoValor(), c.viejoValor());
    }
    return c.viejoValor();
  }

  private static double interp(int x, int x0, int x1, double y0, double y1) {
    return y0 + (y1 - y0) * (x - x0) / (double) (x1 - x0);
  }

  /**
   * Años completos desde el incendio más reciente en o antes de {@code referencia}, o vacío si no
   * hay ninguno. {@code incendios} son las fechas de inicio de los incendios que YA cualifican para
   * el municipio (filtrados por el umbral de reparto). Es por municipio Y fecha: el mismo término
   * da distinto según la fecha de referencia.
   */
  public static OptionalInt aniosDesdeUltimoIncendio(
      LocalDate referencia, List<LocalDate> incendios) {
    LocalDate ultimo = null;
    for (LocalDate f : incendios) {
      if (!f.isAfter(referencia) && (ultimo == null || f.isAfter(ultimo))) {
        ultimo = f;
      }
    }
    return ultimo == null
        ? OptionalInt.empty()
        : OptionalInt.of((int) ChronoUnit.YEARS.between(ultimo, referencia));
  }
}
