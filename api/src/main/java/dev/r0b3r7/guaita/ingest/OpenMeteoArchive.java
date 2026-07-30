package dev.r0b3r7.guaita.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * DTO del payload de una localización de Open-Meteo {@code /v1/archive} (modelo ERA5-Seamless,
 * ADR-07). Solo los campos que consume el mapper; el resto se ignora. Los arrays de {@code hourly}
 * son PARALELOS a {@code time} (misma longitud, mismo índice = misma hora).
 *
 * <p>Verificado contra la API real: con {@code wind_speed_unit=kmh} el viento viene en km/h y
 * {@code relative_humidity_2m} se expone directamente (no hay que derivarla del punto de rocío).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenMeteoArchive(
    double latitude,
    double longitude,
    double elevation,
    @JsonProperty("utc_offset_seconds") int utcOffsetSeconds,
    Hourly hourly) {

  /** Series horarias paralelas. {@code time} en ISO-8601 sin zona (es UTC si utc_offset=0). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Hourly(
      List<String> time,
      @JsonProperty("temperature_2m") List<Double> temperature2m,
      @JsonProperty("relative_humidity_2m") List<Double> relativeHumidity2m,
      @JsonProperty("wind_speed_10m") List<Double> windSpeed10m,
      List<Double> precipitation) {}
}
