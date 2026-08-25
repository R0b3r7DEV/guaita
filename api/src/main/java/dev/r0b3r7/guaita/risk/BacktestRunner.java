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
 * guaita.backtest.run=true}. Corte 2005-2015 (calibración) / 2016-2022 (validación). Imprime
 * AUC-ROC con IC 95%, AUC-PR y el recuento de positivos para el compuesto, las líneas base y las
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
    log.info("backtest del modelo actual (sin calibrar); corte calib<={} …", CORTE);
    List<BacktestService.Metrica>[] res = service.ejecutar(CORTE);
    tabla("CALIBRACION 2005-" + CORTE, res[0]);
    tabla("VALIDACION " + (CORTE + 1) + "-2022", res[1]);

    double[] sc = service.sensYFalsaAlarma(CORTE, false);
    double[] sv = service.sensYFalsaAlarma(CORTE, true);
    log.info(
        "compuesto sens@nivel>=4: calib={} ({} pos)  valid={} ({} pos)",
        p3(sc[0]),
        (long) sc[2],
        p3(sv[0]),
        (long) sv[2]);
    log.info("compuesto falsa alarma@nivel>=4: calib={} valid={}", p3(sc[1]), p3(sv[1]));

    // Chequeo operativo: Villanueva de Viver 2023-03-23 (4.700 ha, temporada baja) por variante.
    double[] v = service.villanueva20230323();
    log.info("== Villanueva de Viver 2023-03-23 (cm_pct={} cm_abs={}) ==", p2(v[0]), p2(v[1]));
    log.info("  compuesto percentil    idx={} nivel={}", p2(v[2]), nivel(v[2]));
    log.info("  compuesto absoluto     idx={} nivel={}", p2(v[3]), nivel(v[3]));
    log.info("  compuesto hibrido_geom idx={} nivel={}", p2(v[4]), nivel(v[4]));
    log.info("  compuesto hibrido_max  idx={} nivel={}", p2(v[5]), nivel(v[5]));
    System.exit(0);
  }

  private static int nivel(double idx) {
    return idx <= 20 ? 1 : idx <= 40 ? 2 : idx <= 60 ? 3 : idx <= 80 ? 4 : 5;
  }

  private static String p2(double v) {
    return String.format(Locale.ROOT, "%.2f", v);
  }

  private void tabla(String periodo, List<BacktestService.Metrica> ms) {
    log.info("== {} ==", periodo);
    for (BacktestService.Metrica m : ms) {
      log.info(
          "  {} AUC={} IC95=[{}, {}] AUC-PR={} nPos={}",
          pad(m.variante()),
          p3(m.auc()),
          p3(m.aucLo()),
          p3(m.aucHi()),
          p3(m.aucPr()),
          m.nPos());
    }
  }

  private static String p3(double v) {
    return String.format(Locale.ROOT, "%.3f", v);
  }

  private static String pad(String s) {
    return String.format("%-30s", s);
  }
}
