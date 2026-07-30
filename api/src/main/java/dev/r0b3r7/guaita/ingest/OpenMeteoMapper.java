package dev.r0b3r7.guaita.ingest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Mapper PURO: payload de Open-Meteo (una localización) -&gt; filas de {@code meteo_municipio}. Sin
 * red, sin BD, sin Spring. Aquí viven las trampas de error silencioso (docs/04, ADR-07):
 *
 * <ul>
 *   <li><b>Hora</b>: se toma SIEMPRE la hora 12:00 UTC. Se exige {@code utc_offset=0} (payload en
 *       UTC); la conversión horaria vive aquí, en un solo sitio.
 *   <li><b>Ventana de lluvia</b>: {@code precip_24h} es la acumulación de las 24 h que TERMINAN a
 *       las 12:00 UTC (de ayer 12 UTC a hoy 12 UTC), NO el día natural. Open-Meteo da la
 *       precipitación de la hora anterior en cada índice, así que se suman las 24 horas
 *       {@code (idx-23 .. idx)} que acaban en el mediodía.
 *   <li><b>Validación</b>: rangos plausibles (T -20..50, HR 0..100, viento&gt;=0, lluvia&gt;=0). Si
 *       UNA fila no valida se rechaza el LOTE ENTERO (T6, docs/07): no se guardan filas parciales.
 * </ul>
 */
public final class OpenMeteoMapper {

  /** Procedencia (columna {@code source} de {@code meteo_municipio}). */
  public static final String SOURCE = "OPEN_METEO_ERA5_SEAMLESS";

  private static final String NOON_SUFFIX = "T12:00";
  private static final int WINDOW_HOURS = 24;

  /**
   * Convierte el payload de una localización en filas diarias para {@code ineCode}. La procedencia
   * es {@code sourceUrl} (la URL exacta de la petición). Lanza si el lote no valida.
   */
  public List<MeteoMunicipio> map(String ineCode, String sourceUrl, OpenMeteoArchive a) {
    if (a.utcOffsetSeconds() != 0) {
      throw new IllegalArgumentException(
          "payload no está en UTC (utc_offset=" + a.utcOffsetSeconds() + "): exige timezone=GMT");
    }
    OpenMeteoArchive.Hourly h = a.hourly();
    List<String> time = h.time();
    List<MeteoMunicipio> out = new ArrayList<>();
    for (int idx = 0; idx < time.size(); idx++) {
      // Solo el mediodía, y solo si hay 23 horas previas para cerrar la ventana de 24 h.
      if (!time.get(idx).endsWith(NOON_SUFFIX) || idx < WINDOW_HOURS - 1) {
        continue;
      }
      LocalDate fecha = LocalDate.parse(time.get(idx).substring(0, 10));
      double temp = req(h.temperature2m(), idx, "temperatura", fecha);
      double hr = req(h.relativeHumidity2m(), idx, "HR", fecha);
      double viento = req(h.windSpeed10m(), idx, "viento", fecha);
      double precip = 0.0;
      for (int j = idx - (WINDOW_HOURS - 1); j <= idx; j++) {
        precip += req(h.precipitation(), j, "precipitación", fecha);
      }
      validar(fecha, temp, hr, viento, precip);
      out.add(
          new MeteoMunicipio(
              ineCode, fecha, temp, hr, viento, precip, true, 1, SOURCE, sourceUrl));
    }
    return out;
  }

  private static double req(List<Double> serie, int idx, String nombre, LocalDate fecha) {
    Double v = (serie != null && idx < serie.size()) ? serie.get(idx) : null;
    if (v == null) {
      throw new IllegalArgumentException("falta " + nombre + " a la hora indexada de " + fecha);
    }
    return v;
  }

  private static void validar(LocalDate f, double t, double hr, double w, double p) {
    if (!(t >= -20.0 && t <= 50.0)) {
      throw new IllegalArgumentException("temperatura implausible en " + f + ": " + t);
    }
    if (!(hr >= 0.0 && hr <= 100.0)) {
      throw new IllegalArgumentException("HR fuera de 0..100 en " + f + ": " + hr);
    }
    if (!(w >= 0.0)) {
      throw new IllegalArgumentException("viento negativo en " + f + ": " + w);
    }
    if (!(p >= 0.0)) {
      throw new IllegalArgumentException("lluvia negativa en " + f + ": " + p);
    }
  }
}
