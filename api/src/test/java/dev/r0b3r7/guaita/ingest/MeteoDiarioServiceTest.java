package dev.r0b3r7.guaita.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import dev.r0b3r7.guaita.TestcontainersConfiguration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * La pasada diaria con Open-Meteo mockeado: recuperación de huecos (el FWI recursivo debe encadenar
 * tras un corte) e idempotencia (dos pasadas seguidas no cambian nada). Aislamiento duro:
 * {@code puntosDeConsulta} hace INNER JOIN con topografia, así que dejando SOLO el municipio de test
 * con topografia, la pasada procesa únicamente ese, sin depender de lo que dejen otros tests.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MeteoDiarioServiceTest {

  private static final String INE = "12099";

  @Autowired private MeteoDiarioService service;
  @Autowired private JdbcTemplate jdbc;
  @MockitoBean private OpenMeteoClient client;

  private LocalDate corte;

  @BeforeEach
  void aislarYStub() {
    jdbc.update("delete from fwi_municipio");
    jdbc.update("delete from meteo_municipio");
    jdbc.update("delete from topografia_municipio");
    jdbc.update("delete from municipio where ine_code = ?", INE);
    jdbc.update(
        "insert into municipio (ine_code, nombre, comarca, geom, superficie_ha, poblacion) values"
            + " (?, 'TestD', 'TestD', st_geomfromtext('MULTIPOLYGON(((750000 4428000,"
            + "751000 4428000,751000 4429000,750000 4429000,750000 4428000)))',25830), 100, 1)",
        INE);
    jdbc.update(
        "insert into topografia_municipio (ine_code, pendiente_media_pct, pendiente_p90_pct,"
            + " frac_solana, altitud_media_m) values (?, 10, 20, 0.5, 800)",
        INE);

    corte = LocalDate.of(2005, 1, 10);
    when(client.corteArchivo(any())).thenReturn(corte);
    when(client.elevacionesNativas(anyList(), any()))
        .thenAnswer(inv -> elevaciones(inv.getArgument(0)));
    when(client.fetch(anyList(), any(), any()))
        .thenAnswer(inv -> meteoDe(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
  }

  @Test
  void ingiereDesdeCeroYEsIdempotente() {
    MeteoDiarioService.Resultado r1 = service.pasada();
    assertEquals(10, r1.diasMeteo(), "de 2005-01-01 al corte 2005-01-10 = 10 días");
    assertEquals(10, contar("meteo_municipio"));
    assertEquals(10, contar("fwi_municipio"));
    assertEquals(0, r1.diasRetraso(), "tras ingerir hasta el corte, sin retraso");

    MeteoDiarioService.Resultado r2 = service.pasada();
    assertEquals(0, r2.diasMeteo(), "segunda pasada seguida no ingiere nada (idempotente)");
    assertEquals(10, contar("meteo_municipio"));
    assertEquals(10, contar("fwi_municipio"));
  }

  @Test
  void recuperaUnHuecoDeVariosDias() {
    service.pasada(); // llena 2005-01-01..2005-01-10

    // Simula una caída de varios días: se pierden meteo Y FWI posteriores al 06.
    jdbc.update("delete from fwi_municipio where fecha > date '2005-01-06'");
    jdbc.update("delete from meteo_municipio where fecha > date '2005-01-06'");
    assertEquals(6, contar("meteo_municipio"));

    MeteoDiarioService.Resultado r = service.pasada();
    assertEquals(4, r.diasMeteo(), "recupera exactamente el hueco (07..10)");
    assertEquals(10, contar("meteo_municipio"));
    assertEquals(10, contar("fwi_municipio"));
    LocalDate max = jdbc.queryForObject("select max(fecha) from fwi_municipio", LocalDate.class);
    assertEquals(corte, max, "la cadena FWI encadena en orden hasta el corte");
  }

  private int contar(String tabla) {
    return jdbc.queryForObject(
        "select count(*) from " + tabla + " where ine_code = ?", Integer.class, INE);
  }

  private static Map<String, Double> elevaciones(List<PuntoMeteo> puntos) {
    Map<String, Double> m = new LinkedHashMap<>();
    for (PuntoMeteo p : puntos) {
      m.put(p.ineCode(), 780.0);
    }
    return m;
  }

  private static List<MeteoMunicipio> meteoDe(
      List<PuntoMeteo> puntos, LocalDate desde, LocalDate hasta) {
    List<MeteoMunicipio> filas = new ArrayList<>();
    for (PuntoMeteo p : puntos) {
      for (LocalDate d = desde; !d.isAfter(hasta); d = d.plusDays(1)) {
        filas.add(new MeteoMunicipio(p.ineCode(), d, 25, 30, 12, 0, 780, 20, "TEST", "u"));
      }
    }
    return filas;
  }
}
