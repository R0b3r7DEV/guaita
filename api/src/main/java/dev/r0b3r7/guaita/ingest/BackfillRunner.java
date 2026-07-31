package dev.r0b3r7.guaita.ingest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  private final DiskGuard diskGuard;

  @Value("${guaita.backfill.run:false}")
  private boolean run;

  @Value("${guaita.backfill.from:2005}")
  private int desde;

  @Value("${guaita.backfill.to:2026}")
  private int hasta;

  @Value("${guaita.backfill.report-path:/out/fwi-backfill.md}")
  private String reportPath;

  // Subconjunto de ine_codes (coma-separado). Vacío = los 135 municipios. Sirve para backfillear
  // solo eventos+control con historia completa cuando el presupuesto de Open-Meteo no da para
  // todos.
  @Value("${guaita.backfill.ine-codes:}")
  private String ineCodes;

  // Tramo final: calcula el FWI de los 135 sobre la serie completa, corre aserciones y genera el
  // informe. Los tramos intermedios (false) solo ingieren meteo -> ingesta order-independent y sin
  // cadenas parciales. Manda la verificación de completitud: un intermedio con finalize=true por
  // error se para si la meteo no está completa, sin romper nada.
  @Value("${guaita.backfill.finalize:false}")
  private boolean finalizar;

  BackfillRunner(
      MeteoMunicipioRepository meteoRepo,
      BackfillService backfill,
      OpenMeteoClient client,
      FwiBackfillReport report,
      DiskGuard diskGuard) {
    this.meteoRepo = meteoRepo;
    this.backfill = backfill;
    this.client = client;
    this.report = report;
    this.diskGuard = diskGuard;
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
    if (!ineCodes.isBlank()) {
      base = filtrar(base, ineCodes);
      log.info("subconjunto de {} municipios: {}", base.size(), ineCodes);
    }
    // Guardia de disco: imprime libre + estimación y se niega a arrancar si no cabe con margen.
    diskGuard.verificarAntesDeArrancar(desde, hasta, base.size());
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
      // Antes de abrir la transacción del año: aborta limpio si el disco baja del umbral.
      diskGuard.verificarAntesDeCadaAño(año);
      int n = backfill.backfillMeteoAño(puntos, año, corte);
      log.info("meteo {} -> {} filas", año, n);
    }
    if (!finalizar) {
      log.info(
          "tramo de meteo {}..{} completado. Sin --finalize: no se calcula FWI ni informe.",
          desde,
          hasta);
      System.exit(0);
      return;
    }

    // Finalización: la meteo DEBE estar completa antes de calcular nada. Un FWI sobre meteo con
    // huecos es una cadena rota que parece sana; la verificación manda.
    List<String> faltan = backfill.verificarMeteoCompleta(corte);
    if (!faltan.isEmpty()) {
      throw new IllegalStateException(
          "--finalize abortado: la meteo no está completa ("
              + faltan.size()
              + " problemas). No se calcula nada.\n  - "
              + String.join("\n  - ", faltan));
    }
    diskGuard.verificarUmbral("cálculo FWI de finalización");
    int n = 0;
    for (String ine : backfill.municipios()) {
      backfill.computeFwiMunicipio(ine);
      n++;
    }
    log.info("FWI calculado/actualizado para {} municipios sobre la serie completa", n);
    report.asserciones(corte);
    String md = report.informe();
    log.info("=== INFORME fwi-backfill ===\n{}", md); // a stdout también, por si el volumen falla
    Files.writeString(Path.of(reportPath), md);
    log.info("informe escrito en {}", reportPath);

    System.exit(0);
  }

  /** Filtra a los ine_codes pedidos; falla si alguno no casa (typo, no silencio). */
  private static List<PuntoMeteo> filtrar(List<PuntoMeteo> base, String csv) {
    Set<String> keep = new HashSet<>();
    for (String s : csv.split(",")) {
      if (!s.isBlank()) {
        keep.add(s.trim());
      }
    }
    List<PuntoMeteo> out = new ArrayList<>();
    for (PuntoMeteo p : base) {
      if (keep.contains(p.ineCode())) {
        out.add(p);
      }
    }
    if (out.size() != keep.size()) {
      throw new IllegalStateException(
          "subconjunto pedía " + keep.size() + " ine_codes pero solo casaron " + out.size());
    }
    return out;
  }
}
