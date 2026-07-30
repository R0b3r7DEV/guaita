package dev.r0b3r7.guaita.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests PUROS del parser + mapper contra un fixture REAL de Open-Meteo (Morella, agosto 2023) y
 * series sintéticas para las trampas: selección de las 12:00 UTC, ventana de lluvia de 24 h que
 * termina al mediodía, calidad del dato (delta de altitud), rechazo del lote entero si algo no
 * valida, guard de UTC y guard de elevación (que se pidió el downscaling a la altitud media).
 */
class OpenMeteoMapperTest {

  private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
  private final OpenMeteoMapper mapper = new OpenMeteoMapper();

  private static PuntoMeteo punto(double altitudMediaM, double elevacionCeldaM) {
    return new PuntoMeteo("12080", -0.101, 40.619, altitudMediaM, elevacionCeldaM);
  }

  @Test
  void mapeaElFixtureRealTomandoLas12UtcYCalculaLaCalidad() throws Exception {
    OpenMeteoArchive a;
    try (InputStream in =
        OpenMeteoMapperTest.class.getResourceAsStream("/openmeteo/archive-morella-2023-08.json")) {
      a = new ObjectMapper().readValue(in, OpenMeteoArchive.class);
    }
    // El fixture se pidió sin elevation -> payload.elevation = 1010 (cota nativa). Se trata como si
    // la altitud media fuese 1010 (para el guard); la celda a 800 -> delta 210.
    List<MeteoMunicipio> rows = mapper.map(punto(1010.0, 800.0), "http://fixture", a);

    // Agosto: 31 días, pero el mediodía del día 1 (idx 12) no tiene 23 horas previas -> 30 filas.
    assertEquals(30, rows.size());
    assertEquals(LocalDate.parse("2023-08-02"), rows.get(0).fecha());
    assertEquals(LocalDate.parse("2023-08-31"), rows.get(29).fecha());

    // Valores de las 12:00 UTC del 14-ago, verificados contra la API real.
    MeteoMunicipio d14 = fecha(rows, "2023-08-14");
    assertEquals(27.7, d14.temp12utcC(), 1e-9);
    assertEquals(48.0, d14.hr12utcPct(), 1e-9);
    assertEquals(19.2, d14.viento12utcKmh(), 1e-9);
    assertEquals(800.0, d14.elevacionCeldaM(), 1e-9);
    assertEquals(210.0, d14.deltaAltitudM(), 1e-9);
    assertEquals(OpenMeteoMapper.SOURCE, d14.source());
  }

  @Test
  void laLluviaDeLas18hVaAlRegistroDelDiaSiguiente() {
    // 5 mm concentrados a las 18:00 del 2023-06-02. El sintético tiene elevation 1000.
    OpenMeteoArchive a = sintetico(LocalDate.parse("2023-06-01"), 3, "2023-06-02T18:00", 5.0);
    List<MeteoMunicipio> rows = mapper.map(punto(1000.0, 1000.0), "http://x", a);

    assertEquals(0.0, fecha(rows, "2023-06-02").precip24hMm(), 1e-9, "no es del mismo día");
    assertEquals(
        5.0, fecha(rows, "2023-06-03").precip24hMm(), 1e-9, "la ventana que acaba a las 12 UTC");
  }

  @Test
  void rechazaElLoteSiUnaFilaNoValida() {
    OpenMeteoArchive a = sintetico(LocalDate.parse("2023-06-01"), 3, null, 0.0);
    a.hourly().relativeHumidity2m().set(a.hourly().time().indexOf("2023-06-02T12:00"), 150.0);
    assertThrows(
        IllegalArgumentException.class, () -> mapper.map(punto(1000.0, 1000.0), "http://x", a));
  }

  @Test
  void rechazaPayloadQueNoEstaEnUtc() {
    OpenMeteoArchive utc = sintetico(LocalDate.parse("2023-06-01"), 2, null, 0.0);
    OpenMeteoArchive noUtc = new OpenMeteoArchive(40.0, 0.0, 1000.0, 3600, utc.hourly());
    assertThrows(
        IllegalArgumentException.class, () -> mapper.map(punto(1000.0, 1000.0), "http://x", noUtc));
  }

  @Test
  void rechazaSiNoSeDownscaleoALaAltitudMedia() {
    // payload.elevation = 1000 pero el municipio pide 500 -> se olvidó elevation en la petición.
    OpenMeteoArchive a = sintetico(LocalDate.parse("2023-06-01"), 2, null, 0.0);
    assertThrows(
        IllegalArgumentException.class, () -> mapper.map(punto(500.0, 400.0), "http://x", a));
  }

  private static MeteoMunicipio fecha(List<MeteoMunicipio> rows, String iso) {
    return rows.stream()
        .filter(r -> r.fecha().equals(LocalDate.parse(iso)))
        .findFirst()
        .orElseThrow();
  }

  private static OpenMeteoArchive sintetico(
      LocalDate inicio, int dias, String horaLluvia, double mm) {
    List<String> time = new ArrayList<>();
    List<Double> temp = new ArrayList<>();
    List<Double> hr = new ArrayList<>();
    List<Double> wind = new ArrayList<>();
    List<Double> precip = new ArrayList<>();
    LocalDateTime t = inicio.atStartOfDay();
    for (int i = 0; i < dias * 24; i++) {
      String s = t.format(FMT);
      time.add(s);
      temp.add(20.0);
      hr.add(50.0);
      wind.add(10.0);
      precip.add(s.equals(horaLluvia) ? mm : 0.0);
      t = t.plusHours(1);
    }
    return new OpenMeteoArchive(
        40.0, 0.0, 1000.0, 0, new OpenMeteoArchive.Hourly(time, temp, hr, wind, precip));
  }
}
