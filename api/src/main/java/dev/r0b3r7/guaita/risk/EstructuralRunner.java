package dev.r0b3r7.guaita.risk;

import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Precálculo puntual de la parte estructural estática (docs/04 §2). Gatillado por {@code
 * guaita.estructural.run=true}. Precalcula, verifica invariantes, contrasta la superficie forestal
 * total contra Fase 1 (~422.000 ha) y saca las tablas de verificación (10 municipios de eventos + 6
 * de control) para ver a ojo que lo que ardió puntúa estructuralmente alto.
 */
@Component
class EstructuralRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(EstructuralRunner.class);

  // 10 municipios de los eventos + 6 de control (docs/03, docs/04).
  private static final List<String> EVENTOS =
      List.of(
          "12022", "12071", "12140", "12114", "12122", "12049", "12060", "12072", "12133", "12126");
  private static final List<String> CONTROL =
      List.of("12139", "12130", "12080", "12082", "12011", "12077");

  private final EstructuralService service;
  private final ModeloParams params;
  private final JdbcTemplate jdbc;
  private final boolean run;

  EstructuralRunner(
      EstructuralService service,
      ModeloParams params,
      JdbcTemplate jdbc,
      @Value("${guaita.estructural.run:false}") boolean run) {
    this.service = service;
    this.params = params;
    this.jdbc = jdbc;
    this.run = run;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!run) {
      return;
    }
    String v = params.version();
    log.info("precálculo estructural {} …", v);
    EstructuralService.Resultado r = service.precomputar(params);
    log.info(
        "estructural_municipio: {} filas; superficie forestal total {} ha (Fase 1 ≈ 422.000)",
        r.filas(),
        Math.round(r.totalForestalHa()));

    List<String> fallos = service.asserciones(v);
    if (!fallos.isEmpty()) {
      throw new IllegalStateException("Aserciones estructural FALLIDAS: " + fallos);
    }
    if (r.totalForestalHa() < 415000 || r.totalForestalHa() > 430000) {
      throw new IllegalStateException(
          "superficie forestal " + Math.round(r.totalForestalHa()) + " ha fuera de ~422.000");
    }
    log.info("aserciones OK; contraste de superficie forestal OK");

    log.info(
        "frac_sin_combustible: {} municipios > 20%; 3 peores:",
        service.municipiosSinCombustibleSobre(0.20));
    jdbc.query(
        "select m.nombre, round((e.frac_sin_combustible*100)::numeric,1) pct"
            + " from estructural_municipio e join municipio m using (ine_code)"
            + " order by e.frac_sin_combustible desc limit 3",
        rs -> log.info("    {} -> {}%", rs.getString("nombre"), rs.getBigDecimal("pct")));
    log.info(
        "f_pendiente saturada a 1.0 en {} de 135 municipios",
        service.municipiosPendienteSaturada());

    log.info("== EVENTOS: factores estructurales + parte estática ==");
    tabla(EVENTOS);
    log.info("== CONTROL ==");
    tabla(CONTROL);
    System.exit(0);
  }

  private void tabla(List<String> ines) {
    String in = String.join(",", ines.stream().map(s -> "'" + s + "'").toList());
    jdbc.query(
        "select e.ine_code, m.nombre, e.frac_forestal ff, e.continuidad co, e.peso_modelo pm,"
            + " e.frac_sin_combustible fsc, e.f_pendiente fp"
            + " from estructural_municipio e join municipio m using (ine_code)"
            + " where e.ine_code in ("
            + in
            + ") order by m.nombre",
        rs -> {
          double ff = rs.getDouble("ff");
          double co = rs.getDouble("co");
          double pm = rs.getDouble("pm");
          double fp = rs.getDouble("fp");
          double pe = Estructural.parteEstatica(ff, co, pm, fp);
          log.info(
              String.format(
                  Locale.ROOT,
                  "  %-24s fracForestal=%.2f cont=%.2f peso=%.2f fPend=%.2f sinComb=%.0f%% -> ESTATICA=%.1f",
                  rs.getString("nombre"), ff, co, pm, fp, rs.getDouble("fsc") * 100, pe));
        });
  }
}
