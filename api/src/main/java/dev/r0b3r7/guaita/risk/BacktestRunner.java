package dev.r0b3r7.guaita.risk;

import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Backtest del modelo ACTUAL sin calibrar (docs/09). Gatillado por {@code
 * guaita.backtest.run=true}. Corte 2005-2015 (calibración) / 2016-2022 (validación). Imprime AUC-ROC
 * con IC 95%, AUC-PR y el recuento de positivos/negativos para el compuesto, las líneas base y las
 * ablaciones, más la sensibilidad y la falsa alarma a nivel ≥ 4. NO fija pesos: la foto de partida.
 */
@Component
class BacktestRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(BacktestRunner.class);
  private static final int CORTE = 2015;

  private final BacktestService service;
  private final boolean run;

  BacktestRunner(BacktestService service, @Value("${guaita.backtest.run:false}") boolean run) {
    this.service = service;
    this.run = run;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!run) {
      return;
    }
    log.info(
        "backtest del modelo actual (sin calibrar); corte calib≤{} / valid>{} …", CORTE, CORTE);
    List<BacktestService.Metrica>[] res = service.ejecutar(CORTE);
    tabla("CALIBRACIÓN 2005-" + CORTE, res[0]);
    tabla("VALIDACIÓN " + (CORTE + 1) + "-2022", res[1]);

    double[] sc = service.sensYFalsaAlarma(CORTE, false);
    double[] sv = service.sensYFalsaAlarma(CORTE, true);
    log.info(
        String.format(
            Locale.ROOT,
            "compuesto sens@nivel≥4: calib=%.2f (%.0f pos) valid=%.2f (%.0f pos)",
            sc[0], sc[2], sv[0], sv[2]));
    log.info(
        String.format(
            Locale.ROOT,
            "compuesto falsa alarma@nivel≥4: calib=%.3f valid=%.3f", sc[1], sv[1]));
    System.exit(0);
  }

  private void tabla(String periodo, List<BacktestService.Metrica> ms) {
    log.info("== {} ==", periodo);
    log.info(
        String.format(Locale.ROOT, "  %-26s %8s %-18s %8s %6s", "variante", "AUC", "IC95", "AUC-PR",
            "nPos"));
    for (BacktestService.Metrica m : ms) {
      log.info(
          String.format(
              Locale.ROOT,
              "  %-26s %8.3f [%.3f,%.3f] %8.3f %6d",
              m.variante(),
              m.auc(),
              m.aucLo(),
              m.aucHi(),
              m.aucPr(),
              m.nPos()));
    }
  }
}
