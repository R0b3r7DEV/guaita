package dev.r0b3r7.guaita.ingest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Entry-point del backfill para el {@code workflow_dispatch} (docs/08). Solo actúa si {@code
 * guaita.backfill.run=true}; el arranque normal de la api no lo dispara. Orquesta: puntos +
 * elevaciones nativas -&gt; meteo por años (recortada al último día del archivo) -&gt; FWI por
 * municipio (reanudable) -&gt; aserciones -&gt; informe. Al terminar, cierra el proceso.
 */
@Component
class BackfillRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(BackfillRunner.class);

  private final MeteoMunicipioRepository meteoRepo;
  private final BackfillService backfill;
  private final OpenMeteoClient client;
  private final FwiBackfillReport report;

  @Value("${guaita.backfill.run:false}")
  private boolean run;

  @Value("${guaita.backfill.from:2005}")
  private int desde;

  @Value("${guaita.backfill.to:2026}")
  private int hasta;

  @Value("${guaita.backfill.report-path:/out/fwi-backfill.md}")
  private String reportPath;

  BackfillRunner(
      MeteoMunicipioRepository meteoRepo,
      BackfillService backfill,
      OpenMeteoClient client,
      FwiBackfillReport report) {
    this.meteoRepo = meteoRepo;
    this.backfill = backfill;
    this.client = client;
    this.report = report;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    if (!run) {
      return;
    }
    List<PuntoMeteo> base = meteoRepo.puntosDeConsulta();
    if (base.isEmpty()) {
      throw new IllegalStateException("no hay municipios/topografía sembrados: ejecuta make seed");
    }
    LocalDate corte = client.corteArchivo(base.get(0));
    log.info(
        "backfill {}..{}, corte del archivo = {}, {} municipios", desde, hasta, corte, base.size());

    Thread.sleep(30_000); // separa la petición de cotas (135 calls) de la sonda de corte
    Map<String, Double> nativas = client.elevacionesNativas(base, corte);
    List<PuntoMeteo> puntos = new ArrayList<>(base.size());
    for (PuntoMeteo p : base) {
      puntos.add(
          new PuntoMeteo(
              p.ineCode(), p.lon(), p.lat(), p.altitudMediaM(), nativas.get(p.ineCode())));
    }

    for (int año = desde; año <= hasta; año++) {
      // Open-Meteo cuenta cada localización como una "call": 135/año. A 40 s ~ 200/min, holgado
      // bajo el límite del tier gratuito (600 calls/min).
      Thread.sleep(40_000);
      int n = backfill.backfillMeteoAño(puntos, año, corte);
      log.info("meteo {} -> {} filas", año, n);
    }
    for (PuntoMeteo p : puntos) {
      backfill.computeFwiMunicipio(p.ineCode());
    }
    report.asserciones();
    String md = report.informe();
    log.info("=== INFORME fwi-backfill ===\n{}", md); // a stdout también, por si el volumen falla
    Files.writeString(Path.of(reportPath), md);
    log.info("informe escrito en {}", reportPath);

    System.exit(0);
  }
}
