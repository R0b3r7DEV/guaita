package dev.r0b3r7.guaita.risk;

import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Precálculo puntual de la climatología FWI congelada (docs/04 §1). Gatillado por {@code
 * guaita.climatologia.run=true} (como el backfill); el arranque normal no lo dispara. Precalcula,
 * verifica invariantes y contrasta los 10 eventos semilla por la NUEVA ruta contra el informe de
 * Fase 2 (misma respuesta calculada por otro camino: si no cuadra, hay un error).
 */
@Component
class ClimatologiaRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(ClimatologiaRunner.class);

  private record Evento(String nombre, String ineCode, LocalDate fecha) {}

  private static final List<Evento> EVENTOS =
      List.of(
          new Evento("Bejís", "12022", LocalDate.parse("2022-08-15")),
          new Evento("Jérica", "12071", LocalDate.parse("2022-08-15")),
          new Evento("Viver", "12140", LocalDate.parse("2022-08-15")),
          new Evento("Torás", "12114", LocalDate.parse("2022-08-15")),
          new Evento("les Useres", "12122", LocalDate.parse("2022-08-15")),
          new Evento("Costur", "12049", LocalDate.parse("2022-08-15")),
          new Evento("Figueroles", "12060", LocalDate.parse("2022-08-15")),
          new Evento("Llucena", "12072", LocalDate.parse("2022-08-15")),
          new Evento("Villanueva de Viver", "12133", LocalDate.parse("2023-03-23")),
          new Evento("la Vall d'Uixó", "12126", LocalDate.parse("2026-07-25")));

  private final ClimatologiaService service;
  private final ModeloParams params;
  private final boolean run;

  ClimatologiaRunner(
      ClimatologiaService service,
      ModeloParams params,
      @org.springframework.beans.factory.annotation.Value("${guaita.climatologia.run:false}")
          boolean run) {
    this.service = service;
    this.params = params;
    this.run = run;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!run) {
      return;
    }
    String v = params.version();
    ModeloParams.Meteo m = params.meteo();
    log.info(
        "precálculo climatología {}: base {}..{}, ventana ±{} días",
        v,
        m.baseDesde(),
        m.baseHasta(),
        m.ventanaDias());
    int n = service.precomputar(v, m.ventanaDias(), m.baseDesde(), m.baseHasta());
    log.info("fwi_climatologia: {} filas insertadas", n);

    List<String> fallos = service.asserciones(v);
    if (!fallos.isEmpty()) {
      throw new IllegalStateException("Aserciones de climatología FALLIDAS: " + fallos);
    }
    log.info("aserciones OK (135×366, breakpoints=101, monótonos)");

    log.info("== percentil de los 10 eventos por la NUEVA ruta (vs base {}) ==", m.baseHasta());
    for (Evento e : EVENTOS) {
      Double p = service.percentilEvento(e.ineCode(), e.fecha(), v);
      log.info(
          "  {} ({}) -> {}",
          e.nombre(),
          e.fecha(),
          p == null ? "sin dato" : String.format(java.util.Locale.ROOT, "P%.1f", p));
    }
    System.exit(0);
  }
}
