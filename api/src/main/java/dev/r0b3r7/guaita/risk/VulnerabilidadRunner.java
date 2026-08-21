package dev.r0b3r7.guaita.risk;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Precálculo puntual del componente de vulnerabilidad provisional (docs/04 §3). Gatillado por
 * {@code guaita.vulnerab.run=true}. Precalcula, verifica invariantes y saca las tablas para ver a
 * ojo que las grandes poblaciones y los términos con mucho suelo protegido (Desert de les Palmes)
 * puntúan alto pese a poca masa forestal.
 */
@Component
class VulnerabilidadRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(VulnerabilidadRunner.class);

  private final VulnerabilidadService service;
  private final ModeloParams params;
  private final JdbcTemplate jdbc;
  private final boolean run;

  VulnerabilidadRunner(
      VulnerabilidadService service,
      ModeloParams params,
      JdbcTemplate jdbc,
      @Value("${guaita.vulnerab.run:false}") boolean run) {
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
    log.info("precálculo vulnerabilidad provisional {} …", v);
    VulnerabilidadService.Resultado r = service.precomputar(params);
    log.info(
        "vulnerab_municipio: {} filas; suelo protegido intersecado {} ha",
        r.filas(),
        Math.round(r.protegidoHa()));

    List<String> fallos = service.asserciones(v);
    if (!fallos.isEmpty()) {
      throw new IllegalStateException("Aserciones vulnerabilidad FALLIDAS: " + fallos);
    }
    log.info("aserciones OK");

    log.info("== 8 municipios con MAYOR comp_vulnerab ==");
    tabla(
        "select m.nombre, m.poblacion, round(vm.poblacion_norm,3) pn,"
            + " round(vm.frac_espacio_protegido,3) fp, vm.comp_vulnerab cv"
            + " from vulnerab_municipio vm join municipio m using (ine_code)"
            + " order by vm.comp_vulnerab desc limit 8");
    log.info("== 8 municipios con MAYOR fracción de suelo protegido ==");
    tabla(
        "select m.nombre, m.poblacion, round(vm.poblacion_norm,3) pn,"
            + " round(vm.frac_espacio_protegido,3) fp, vm.comp_vulnerab cv"
            + " from vulnerab_municipio vm join municipio m using (ine_code)"
            + " order by vm.frac_espacio_protegido desc limit 8");
    System.exit(0);
  }

  private void tabla(String sql) {
    jdbc.query(
        sql,
        (java.sql.ResultSet rs) -> {
          log.info(
              "  {} pob={} pobNorm={} fracProt={} -> comp_vulnerab={}",
              String.format("%-26s", rs.getString("nombre")),
              rs.getInt("poblacion"),
              rs.getBigDecimal("pn"),
              rs.getBigDecimal("fp"),
              rs.getBigDecimal("cv"));
        });
  }
}
