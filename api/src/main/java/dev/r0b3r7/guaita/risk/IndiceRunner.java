package dev.r0b3r7.guaita.risk;

import java.time.LocalDate;
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
 * Cálculo puntual del índice compuesto del día más reciente (docs/04 §4). Gatillado por {@code
 * guaita.indice.run=true}. Calcula, verifica invariantes y saca las tablas de verificación (reparto
 * por nivel, top-10, control, efecto de f_tiempo en la Vall d'Uixó, regla del 30).
 */
@Component
class IndiceRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(IndiceRunner.class);

  private static final List<String> CONTROL =
      List.of("12139", "12130", "12080", "12082", "12011", "12077");

  private final IndiceService service;
  private final ModeloParams params;
  private final JdbcTemplate jdbc;
  private final boolean run;

  IndiceRunner(
      IndiceService service,
      ModeloParams params,
      JdbcTemplate jdbc,
      @Value("${guaita.indice.run:false}") boolean run) {
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
    log.info("cálculo del índice compuesto {} …", params.version());
    IndiceService.Resultado r = service.calcularHoy(params);

    List<String> fallos = service.asserciones(r.fecha());
    if (!fallos.isEmpty()) {
      throw new IllegalStateException("Aserciones índice FALLIDAS: " + fallos);
    }
    log.info("indice_peligro: {} filas para {}; aserciones OK", r.filas(), r.fecha());

    // a) reparto por nivel
    int[] n = r.porNivel();
    log.info(
        "== a) reparto por nivel (1 bajo .. 5 extremo): {} / {} / {} / {} / {} ==",
        n[0],
        n[1],
        n[2],
        n[3],
        n[4]);

    // b) top-10 provincial con desglose
    log.info("== b) TOP-10 provincial de hoy ==");
    tabla(
        "select m.nombre, ip.comp_meteo cm, ip.comp_estructural ce, ip.comp_vulnerab cv,"
            + " ip.indice, ip.nivel, ip.alerta_30_30_30 a30"
            + " from indice_peligro ip join municipio m using (ine_code)"
            + " where ip.fecha = ? order by ip.indice desc limit 10",
        r.fecha());

    // c) control
    log.info("== c) municipios de control ==");
    tabla(
        "select m.nombre, ip.comp_meteo cm, ip.comp_estructural ce, ip.comp_vulnerab cv,"
            + " ip.indice, ip.nivel, ip.alerta_30_30_30 a30"
            + " from indice_peligro ip join municipio m using (ine_code)"
            + " where ip.fecha = ? and ip.ine_code in ('12139','12130','12080','12082','12011','12077')"
            + " order by ip.indice desc",
        r.fecha());

    // d) la Vall d'Uixó: efecto de f_tiempo=0.15 sobre la parte estática
    efectoFTiempo(r.fecha(), "12126");

    // e) regla del 30-30-30
    Integer con303030 =
        jdbc.queryForObject(
            "select count(*) from indice_peligro where fecha = ? and alerta_30_30_30",
            Integer.class,
            r.fecha());
    log.info("== e) municipios con regla 30-30-30 activa hoy: {} ==", con303030);
    System.exit(0);
  }

  private void efectoFTiempo(LocalDate fecha, String ine) {
    var f = jdbc.queryForMap("select * from estructural_municipio where ine_code = ?", ine);
    double parte =
        Estructural.parteEstatica(
            ((Number) f.get("frac_forestal")).doubleValue(),
            ((Number) f.get("continuidad")).doubleValue(),
            ((Number) f.get("peso_modelo")).doubleValue(),
            ((Number) f.get("f_pendiente")).doubleValue());
    var ip =
        jdbc.queryForMap(
            "select m.nombre, ip.comp_estructural, ip.indice, ip.nivel"
                + " from indice_peligro ip join municipio m using (ine_code)"
                + " where ip.ine_code = ? and ip.fecha = ?",
            ine,
            fecha);
    log.info(
        String.format(
            Locale.ROOT,
            "== d) %s: parte_estatica=%.1f -> comp_estructural=%.1f (f_tiempo hunde por incendio"
                + " reciente); indice=%.1f nivel=%s ==",
            ip.get("nombre"),
            parte,
            ((Number) ip.get("comp_estructural")).doubleValue(),
            ((Number) ip.get("indice")).doubleValue(),
            ip.get("nivel")));
  }

  private void tabla(String sql, LocalDate fecha) {
    jdbc.query(
        sql,
        (java.sql.ResultSet rs) -> {
          log.info(
              String.format(
                  Locale.ROOT,
                  "  %-26s meteo=%5.1f estruct=%5.1f vuln=%5.1f -> indice=%5.1f nivel=%d %s",
                  rs.getString("nombre"),
                  rs.getBigDecimal("cm"),
                  rs.getBigDecimal("ce"),
                  rs.getBigDecimal("cv"),
                  rs.getBigDecimal("indice"),
                  rs.getInt("nivel"),
                  rs.getBoolean("a30") ? "[30-30-30]" : ""));
        },
        fecha);
  }
}
