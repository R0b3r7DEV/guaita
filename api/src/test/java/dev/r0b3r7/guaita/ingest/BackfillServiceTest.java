package dev.r0b3r7.guaita.ingest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.r0b3r7.guaita.TestcontainersConfiguration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test de la trampa del backfill: el FWI es recursivo y reanudable, así que calcular un rango DE
 * GOLPE debe dar EXACTAMENTE lo mismo que calcularlo en tramos interrumpidos (que reanudan leyendo
 * el estado previo de la BD). Si al reanudar se reiniciara con 85/6/15, el DC se resetearía a mitad
 * de serie y este test lo cazaría.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BackfillServiceTest {

  @Autowired private BackfillService service;
  @Autowired private MeteoMunicipioRepository meteoRepo;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void sembrarMeteo() {
    jdbc.update("delete from fwi_municipio where ine_code = '12080'");
    jdbc.update("delete from meteo_municipio where ine_code = '12080'");
    jdbc.update("delete from municipio where ine_code = '12080'");
    jdbc.update(
        "insert into municipio (ine_code, nombre, comarca, geom, superficie_ha, poblacion) values"
            + " ('12080','Test','Test', st_geomfromtext('MULTIPOLYGON(((750000 4428000,"
            + "751000 4428000,751000 4429000,750000 4429000,750000 4428000)))',25830), 100, 1)");
    // 40 días consecutivos desde el inicio de la serie, con algo de lluvia periódica para que el
    // DC evolucione (calor sostenido lo sube; la lluvia lo baja).
    jdbc.update(
        "insert into meteo_municipio (ine_code, fecha, temp_12utc_c, hr_12utc_pct,"
            + " viento_12utc_kmh, precip_24h_mm, elevacion_celda_m, delta_altitud_m, source,"
            + " source_url) select '12080', d::date, 30, 25, 15,"
            + " case when extract(day from d)::int % 7 = 0 then 8.0 else 0.0 end,"
            + " 800, 0, 'TEST', 'u'"
            + " from generate_series(date '2005-01-01', date '2005-02-09', interval '1 day') d");
  }

  @Test
  void reanudarEnTramosDaLoMismoQueDeGolpe() {
    service.computeFwiMunicipio("12080");
    List<double[]> deGolpe = serieFwi();
    assertEquals(40, deGolpe.size());
    assertTrue(deGolpe.get(39)[2] > 30.0, "el DC no evolucionó (¿arranque siempre en 15?)");

    // Simular dos cortes: la BD solo conserva hasta el día 10, luego hasta el 25.
    jdbc.update("delete from fwi_municipio where ine_code = '12080' and fecha > date '2005-01-10'");
    service.computeFwiMunicipio("12080"); // reanuda desde el 10
    jdbc.update("delete from fwi_municipio where ine_code = '12080' and fecha > date '2005-01-25'");
    service.computeFwiMunicipio("12080"); // reanuda desde el 25

    List<double[]> reanudado = serieFwi();
    assertEquals(deGolpe.size(), reanudado.size());
    for (int i = 0; i < deGolpe.size(); i++) {
      assertArrayEquals(deGolpe.get(i), reanudado.get(i), 1e-9, "difiere en el día índice " + i);
    }
  }

  @Test
  void marcaCalentamientoSoloLosPrimeros30Dias() {
    service.computeFwiMunicipio("12080");
    Integer cal =
        jdbc.queryForObject(
            "select count(*) from fwi_municipio where ine_code = '12080' and calentamiento",
            Integer.class);
    assertEquals(30, cal, "deben marcarse exactamente los 30 primeros días");
  }

  @Test
  void verificarMeteoCompletaCazaHuecosYFaltantes() {
    // 12080 tiene meteo 2005-01-01..2005-02-09 completa: no debe aparecer como problema.
    LocalDate corte = LocalDate.of(2005, 2, 9);
    List<String> completa = service.verificarMeteoCompleta(corte);
    assertTrue(
        completa.stream().noneMatch(s -> s.contains("12080")),
        "12080 está completo, no debería listarse: " + completa);

    // Un hueco interno debe delatarse con su ine_code.
    jdbc.update(
        "delete from meteo_municipio where ine_code = '12080' and fecha = date '2005-01-15'");
    List<String> conHueco = service.verificarMeteoCompleta(corte);
    assertTrue(
        conHueco.stream().anyMatch(s -> s.contains("12080")),
        "el hueco de 12080 debería delatarse: " + conHueco);
  }

  @Test
  void relanzarUnTramoCompletoNoRecalcula() {
    int primera = service.computeFwiMunicipio("12080");
    assertEquals(40, primera);
    Integer antes =
        jdbc.queryForObject(
            "select count(*) from fwi_municipio where ine_code = '12080'", Integer.class);

    int segunda = service.computeFwiMunicipio("12080"); // ya está completo hasta el último día
    Integer despues =
        jdbc.queryForObject(
            "select count(*) from fwi_municipio where ine_code = '12080'", Integer.class);
    assertEquals(0, segunda, "un tramo ya completo no recalcula nada");
    assertEquals(antes, despues, "ni duplica filas");
  }

  @Test
  void upsertMeteoEnDosTramosNoDuplica() {
    // Ingerir en dos tramos separados == ingerirlo de una vez (upsert idempotente por PK).
    meteoRepo.upsertAll(List.of(meteo("2010-06-01", 28), meteo("2010-06-02", 29)));
    meteoRepo.upsertAll(List.of(meteo("2010-06-02", 31), meteo("2010-06-03", 30))); // solapa 06-02

    Integer n =
        jdbc.queryForObject(
            "select count(*) from meteo_municipio where ine_code = '12080'"
                + " and fecha between date '2010-06-01' and date '2010-06-03'",
            Integer.class);
    assertEquals(3, n, "tres días distintos, sin duplicar el solapado");
    Double temp02 =
        jdbc.queryForObject(
            "select temp_12utc_c from meteo_municipio where ine_code = '12080'"
                + " and fecha = date '2010-06-02'",
            Double.class);
    assertEquals(31.0, temp02, 1e-9, "el solapado quedó con el último valor (upsert)");
  }

  private static MeteoMunicipio meteo(String fecha, double temp) {
    return new MeteoMunicipio("12080", LocalDate.parse(fecha), temp, 30, 12, 0, 800, 0, "T", "u");
  }

  private List<double[]> serieFwi() {
    return jdbc.query(
        "select ffmc, dmc, dc, fwi from fwi_municipio where ine_code = '12080' order by fecha",
        (rs, n) ->
            new double[] {
              rs.getDouble("ffmc"), rs.getDouble("dmc"), rs.getDouble("dc"), rs.getDouble("fwi")
            });
  }
}
