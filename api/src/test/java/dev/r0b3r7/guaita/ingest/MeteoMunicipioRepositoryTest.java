package dev.r0b3r7.guaita.ingest;

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
 * Test del repositorio (Testcontainers). Siembra un municipio con continente (grande) + isla lejana
 * (pequeña), como Castelló con las Columbretes, y su fila de topografía (altitud media), para
 * comprobar que el punto de consulta cae en el continente y trae la altitud; y que el upsert es
 * idempotente por {@code (ine_code, fecha)}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MeteoMunicipioRepositoryTest {

  @Autowired private MeteoMunicipioRepository repo;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void sembrar() {
    jdbc.update("delete from meteo_municipio where ine_code = '12040'");
    jdbc.update("delete from topografia_municipio where ine_code = '12040'");
    jdbc.update("delete from municipio where ine_code = '12040'");
    // Continente ~24 km² alrededor de Castelló + una isla diminuta muy al este (las Columbretes).
    jdbc.update(
        "insert into municipio (ine_code, nombre, comarca, geom, superficie_ha, poblacion)"
            + " values ('12040','Castelló','La Plana Alta', st_geomfromtext('MULTIPOLYGON("
            + "((750000 4428000,756000 4428000,756000 4432000,750000 4432000,750000 4428000)),"
            + "((789000 4435000,789200 4435000,789200 4435200,789000 4435200,789000 4435000)))',"
            + "25830), 2404, 1)");
    jdbc.update(
        "insert into topografia_municipio"
            + " (ine_code, pendiente_media_pct, pendiente_p90_pct, frac_solana, altitud_media_m)"
            + " values ('12040', 5.0, 10.0, 0.5, 800.0)");
  }

  @Test
  void elPuntoDeConsultaCaeEnElContinenteYTraeLaAltitud() {
    List<PuntoMeteo> puntos = repo.puntosDeConsulta();
    PuntoMeteo p =
        puntos.stream().filter(x -> x.ineCode().equals("12040")).findFirst().orElseThrow();
    // El continente cae en ~lon [-0,06..0,0]; la isla, mucho más al este (~lon 0,4).
    assertTrue(p.lon() < 0.2, "el punto se fue a la isla (lon=" + p.lon() + ")");
    assertEquals(800.0, p.altitudMediaM(), 1e-9, "no trajo la altitud media");
    assertEquals(0, repo.municipiosConPuntoFuera(), "algún punto cae fuera de su término");
  }

  @Test
  void elUpsertEsIdempotentePorIneCodeYFecha() {
    LocalDate f = LocalDate.parse("2023-08-14");
    MeteoMunicipio v1 =
        new MeteoMunicipio("12040", f, 27.7, 48, 19.2, 0.0, 600.0, 200.0, "S", "u1");
    MeteoMunicipio v2 =
        new MeteoMunicipio("12040", f, 30.0, 40, 25.0, 1.5, 600.0, 200.0, "S", "u2");
    repo.upsertAll(List.of(v1));
    repo.upsertAll(List.of(v2)); // mismo (ine_code, fecha) -> UPDATE, no duplica

    Integer n =
        jdbc.queryForObject(
            "select count(*) from meteo_municipio where ine_code = '12040'", Integer.class);
    assertEquals(1, n, "el upsert duplicó filas");
    Double temp =
        jdbc.queryForObject(
            "select temp_12utc_c from meteo_municipio where ine_code = '12040' and fecha = ?",
            Double.class,
            f);
    assertEquals(30.0, temp, 1e-9, "el upsert no actualizó el valor");
  }
}
