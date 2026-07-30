package dev.r0b3r7.guaita.web.tiles;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wdtinc.mapbox_vector_tile.adapt.jts.MvtReader;
import com.wdtinc.mapbox_vector_tile.adapt.jts.TagKeyValueMapConverter;
import com.wdtinc.mapbox_vector_tile.adapt.jts.model.JtsLayer;
import com.wdtinc.mapbox_vector_tile.adapt.jts.model.JtsMvt;
import dev.r0b3r7.guaita.TestcontainersConfiguration;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Test de integración del endpoint MVT. Decodifica el tile que sale por HTTP y comprueba que la
 * feature de Castelló (INE 12040) viaja con sus atributos —probar solo el status 200 no probaría
 * nada—. También el 204 (tesela sin geometría) y el 400 (zoom fuera de rango).
 *
 * <p>El contenedor no ejecuta el seed; se inserta la huella real aproximada de Castelló.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MunicipioTilesControllerTest {

  private static final double LON = -0.0376;
  private static final double LAT = 39.9864;
  private static final String URL = "/api/v1/tiles/municipios/{z}/{x}/{y}.mvt";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void sembrarCastello() {
    jdbc.update("delete from municipio where ine_code = '12040'");
    jdbc.update(
        "insert into municipio (ine_code, nombre, comarca, geom, superficie_ha, poblacion) "
            + "select '12040', 'Castelló de la Plana', 'La Plana Alta', g,"
            + " st_area(g) / 10000.0, 1 from ("
            + "  select st_multi(st_buffer("
            + "    st_transform(st_setsrid(st_makepoint(?, ?), 4326), 25830), 2000)) g) t",
        LON,
        LAT);
  }

  @Test
  void elTileZ12DeCastelloTraeSuFeatureDecodificada() throws Exception {
    int[] t = TileMath.tileForLonLat(12, LON, LAT);
    byte[] bytes =
        mvc.perform(get(URL, 12, t[0], t[1]))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MunicipioTilesController.MVT_TYPE))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    // ST_AsMVT emite tiles con winding de la spec 2.1: hay que decodificar con el clasificador de
    // anillos V2.1, no el V1 (que dejaría los polígonos vacíos y la capa sin geometrías).
    JtsMvt mvt =
        MvtReader.loadMvt(
            new ByteArrayInputStream(bytes),
            new GeometryFactory(),
            new TagKeyValueMapConverter(),
            MvtReader.RING_CLASSIFIER_V2_1);
    JtsLayer capa = mvt.getLayer("municipios");
    assertNotNull(capa, "el tile no trae la capa 'municipios'");

    List<String> diag = new ArrayList<>();
    boolean encontrado = false;
    for (Geometry g : capa.getGeometries()) {
      Object ud = g.getUserData();
      diag.add(g.getGeometryType() + " ud=" + ud);
      // Los valores de atributo MVT no siempre decodifican a String: se compara la forma textual.
      if (ud instanceof Map<?, ?> attrs && "12040".equals(String.valueOf(attrs.get("ine_code")))) {
        encontrado = true;
        assertNotNull(attrs.get("nombre"), "la feature de Castelló no trae 'nombre'");
      }
    }
    assertTrue(encontrado, "sin feature 12040. n=" + capa.getGeometries().size() + " " + diag);
  }

  @Test
  void teselaSinGeometriaDevuelve204() throws Exception {
    // z=12 en mitad del Atlántico (lon -30, lat 40): ningún municipio.
    int[] t = TileMath.tileForLonLat(12, -30.0, 40.0);
    mvc.perform(get(URL, 12, t[0], t[1])).andExpect(status().isNoContent());
  }

  @Test
  void zoomPorEncimaDe16Devuelve400() throws Exception {
    mvc.perform(get(URL, 17, 0, 0)).andExpect(status().isBadRequest());
  }
}
