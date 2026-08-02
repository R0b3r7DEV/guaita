package dev.r0b3r7.guaita.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

/**
 * La pasada diaria debe dispararse a la MISMA hora local de Madrid en verano y en invierno, con la
 * hora UTC cambiando con el horario de verano. Si se hubiera fijado en UTC (o heredado la zona del
 * contenedor), la hora local se desplazaría media temporada.
 */
class MeteoSchedulerCronTest {

  @Test
  void disparaALas0630DeMadridEnInviernoYVerano() {
    CronExpression cron = CronExpression.parse(MeteoScheduler.CRON);
    ZoneId madrid = ZoneId.of(MeteoScheduler.ZONA);

    // Invierno (CET, UTC+1): 06:30 local = 05:30 UTC.
    ZonedDateTime inv = cron.next(ZonedDateTime.of(2025, 1, 15, 0, 0, 0, 0, madrid));
    assertEquals(6, inv.getHour(), "hora local en invierno");
    assertEquals(30, inv.getMinute());
    assertEquals(
        5, inv.withZoneSameInstant(ZoneOffset.UTC).getHour(), "invierno 06:30 CET = 05:30 UTC");

    // Verano (CEST, UTC+2): 06:30 local = 04:30 UTC.
    ZonedDateTime ver = cron.next(ZonedDateTime.of(2025, 7, 15, 0, 0, 0, 0, madrid));
    assertEquals(6, ver.getHour(), "hora local en verano");
    assertEquals(30, ver.getMinute());
    assertEquals(
        4, ver.withZoneSameInstant(ZoneOffset.UTC).getHour(), "verano 06:30 CEST = 04:30 UTC");
  }
}
