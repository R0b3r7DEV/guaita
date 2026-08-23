package dev.r0b3r7.guaita.risk;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Backfill histórico del índice compuesto (paso 10): indice_peligro para toda la serie 2005→hoy,
 * los 135 municipios. Gatillado por {@code guaita.indice.backfill.run=true}. Sin red. Calcula,
 * verifica invariantes y refresca mv_indice_hoy. Las tablas de verificación (niveles por año/mes,
 * los 10 eventos, la serie de la Vall d'Uixó) se sacan por SQL tras la ejecución.
 */
@Component
class IndiceBackfillRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(IndiceBackfillRunner.class);

  private final IndiceService service;
  private final ModeloParams params;
  private final boolean run;

  IndiceBackfillRunner(
      IndiceService service,
      ModeloParams params,
      @Value("${guaita.indice.backfill.run:false}") boolean run) {
    this.service = service;
    this.params = params;
    this.run = run;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!run) {
      return;
    }
    log.info(
        "backfill histórico del índice {} … (sin red, puede tardar unos minutos)",
        params.version());
    IndiceService.BackfillResultado r = service.backfillHistorico(params);
    log.info(
        "indice_peligro: {} filas, {} municipios, {} .. {}",
        r.filas(),
        r.municipios(),
        r.desde(),
        r.hasta());

    List<String> fallos = service.asercionesBackfill(params.version());
    if (!fallos.isEmpty()) {
      throw new IllegalStateException("Aserciones backfill FALLIDAS: " + fallos);
    }
    log.info("aserciones OK (filas, nivel coherente, invariante Vall d'Uixó)");
    System.exit(0);
  }
}
