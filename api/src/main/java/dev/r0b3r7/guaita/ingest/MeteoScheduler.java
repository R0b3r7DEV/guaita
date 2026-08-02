package dev.r0b3r7.guaita.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispara la pasada diaria de meteo + FWI ({@link MeteoDiarioService}). Apagado salvo que {@code
 * guaita.scheduler.enabled=true} (así los tests con contexto no lo activan; en el VPS se enciende).
 *
 * <p><b>Zona horaria EXPLÍCITA</b> {@code Europe/Madrid}: el contenedor corre en UTC, pero queremos
 * la hora local (06:30 CET/CEST) y que el cambio de hora se respete solo. Nunca heredar la zona del
 * entorno. Un fallo de la pasada se registra y NO se propaga: la siguiente recupera los huecos.
 */
@Component
@ConditionalOnProperty(name = "guaita.scheduler.enabled", havingValue = "true")
public class MeteoScheduler {

  /** Cron y zona; constantes para poder verificarlos en test sin esperar al reloj. */
  public static final String CRON = "0 30 6 * * *";

  public static final String ZONA = "Europe/Madrid";

  private static final Logger log = LoggerFactory.getLogger(MeteoScheduler.class);

  private final MeteoDiarioService service;

  public MeteoScheduler(MeteoDiarioService service) {
    this.service = service;
  }

  @Scheduled(cron = CRON, zone = ZONA)
  public void diaria() {
    try {
      MeteoDiarioService.Resultado r = service.pasada();
      log.info(
          "pasada diaria OK: rango={}..{} municipios={} diasMeteo={} fwi={} retraso={}d",
          r.desde(),
          r.hasta(),
          r.municipios(),
          r.diasMeteo(),
          r.fwiActualizado(),
          r.diasRetraso());
      if (r.diasRetraso() > 0) {
        log.warn(
            "retraso de {} d respecto al corte del archivo; vigilar si crece", r.diasRetraso());
      }
    } catch (RuntimeException e) {
      // Fallo de fuente/validación: no se ha escrito nada. La siguiente pasada recupera el hueco.
      log.error("pasada diaria FALLÓ (se recupera en la siguiente): {}", e.getMessage(), e);
    }
  }
}
