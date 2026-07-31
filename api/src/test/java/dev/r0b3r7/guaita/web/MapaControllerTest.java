package dev.r0b3r7.guaita.web;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.r0b3r7.guaita.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Test de {@code /api/v1/mapa/extent}: la envolvente continental debe caer sobre Castellón (no en
 * mitad del mar por las Columbretes). Se siembra la huella de Castelló y se refresca la vista
 * materializada, igual que hace el seed.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MapaControllerTest {

  private static final double LON = -0.0376;
  private static final double LAT = 39.9864;

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void sembrarYRefrescar() {
    jdbc.update("delete from meteo_municipio where ine_code = '12040'");
    jdbc.update("delete from topografia_municipio where ine_code = '12040'");
    jdbc.update("delete from municipio where ine_code = '12040'");
    jdbc.update(
        "insert into municipio (ine_code, nombre, comarca, geom, superficie_ha, poblacion) "
            + "select '12040', 'Castelló de la Plana', 'La Plana Alta', g,"
            + " st_area(g) / 10000.0, 1 from ("
            + "  select st_multi(st_buffer("
            + "    st_transform(st_setsrid(st_makepoint(?, ?), 4326), 25830), 2000)) g) t",
        LON,
        LAT);
    jdbc.execute("refresh materialized view mv_provincia_continental");
  }

  @Test
  void elExtentContinentalCaeSobreCastellon() throws Exception {
    String body =
        mvc.perform(get("/api/v1/mapa/extent"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // body = {"bbox":[minLon,minLat,maxLon,maxLat]}. Se parsea sin dependencia JSON extra.
    // Huella de ~2 km alrededor de Castelló: bbox aprox. lon[-0,06..-0,01], lat[39,97..40,00].
    String inner = body.substring(body.indexOf('[') + 1, body.indexOf(']'));
    String[] p = inner.split(",");
    double minLon = Double.parseDouble(p[0].trim());
    double minLat = Double.parseDouble(p[1].trim());
    double maxLon = Double.parseDouble(p[2].trim());
    double maxLat = Double.parseDouble(p[3].trim());
    assertTrue(minLon > -1.0 && minLon < 1.0, "minLon fuera de la provincia: " + minLon);
    assertTrue(maxLon > -1.0 && maxLon < 1.0, "maxLon fuera de la provincia: " + maxLon);
    assertTrue(minLat > 38.0 && minLat < 42.0, "minLat fuera de la provincia: " + minLat);
    assertTrue(maxLat > 38.0 && maxLat < 42.0, "maxLat fuera de la provincia: " + maxLat);
  }
}
