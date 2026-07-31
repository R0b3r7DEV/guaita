package dev.r0b3r7.guaita.ingest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.r0b3r7.guaita.TestcontainersConfiguration;
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

  private List<double[]> serieFwi() {
    return jdbc.query(
        "select ffmc, dmc, dc, fwi from fwi_municipio where ine_code = '12080' order by fecha",
        (rs, n) ->
            new double[] {
              rs.getDouble("ffmc"), rs.getDouble("dmc"), rs.getDouble("dc"), rs.getDouble("fwi")
            });
  }
}
