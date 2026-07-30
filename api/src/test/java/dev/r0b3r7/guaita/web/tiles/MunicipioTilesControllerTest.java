package dev.r0b3r7.guaita.web.tiles;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wdtinc.mapbox_vector_tile.adapt.jts.JtsLayer;
import com.wdtinc.mapbox_vector_tile.adapt.jts.JtsMvt;
import com.wdtinc.mapbox_vector_tile.adapt.jts.MvtReader;
import com.wdtinc.mapbox_vector_tile.adapt.jts.TagKeyValueMapConverter;
import dev.r0b3r7.guaita.TestcontainersConfiguration;
import java.io.ByteArrayInputStream;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

    JtsMvt mvt =
        MvtReader.loadMvt(
            new ByteArrayInputStream(bytes),
            new GeometryFactory(),
            new TagKeyValueMapConverter());
    JtsLayer capa = mvt.getLayer("municipios");
    assertNotNull(capa, "el tile no trae la capa 'municipios'");

    boolean encontrado = false;
    for (Geometry g : capa.getGeometries()) {
      Map<?, ?> attrs = (Map<?, ?>) g.getUserData();
      if (attrs != null && "12040".equals(attrs.get("ine_code"))) {
        encontrado = true;
        assertNotNull(attrs.get("nombre"), "la feature de Castelló no trae 'nombre'");
      }
    }
    assertTrue(encontrado, "el tile decodificado no contiene la feature de Castelló (12040)");
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
