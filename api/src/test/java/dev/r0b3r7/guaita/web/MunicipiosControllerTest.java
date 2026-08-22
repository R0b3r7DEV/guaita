package dev.r0b3r7.guaita.web;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
 * Integración de los endpoints de índice (docs/06). Siembra el grafo mínimo de la Vall d'Uixó
 * (12126) —municipio, meteo, FWI, factores e índice de dos días—, refresca {@code mv_indice_hoy} y
 * verifica que el JSON trae los tres componentes, las banderas con la forma correcta
 * (vientoAlineado null, no false) y calidadDato poblado. El contenedor no ejecuta el seed.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MunicipiosControllerTest {

  private static final String INE = "12126";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void sembrar() {
    for (String t :
        new String[] {
          "indice_peligro", "fwi_municipio", "meteo_municipio", "estructural_municipio",
          "vulnerab_municipio"
        }) {
      jdbc.update("delete from " + t + " where ine_code = ?", INE);
    }
    jdbc.update("delete from municipio where ine_code = ?", INE);

    jdbc.update(
        "insert into municipio (ine_code, nombre, comarca, geom, superficie_ha, poblacion)"
            + " select ?, 'la Vall d''Uixó', 'Plana Baixa', g, st_area(g)/10000.0, 32242 from ("
            + "  select st_multi(st_buffer("
            + "    st_transform(st_setsrid(st_makepoint(-0.23, 39.83), 4326), 25830), 2000)) g) t",
        INE);

    jdbc.update(
        "insert into estructural_municipio (ine_code, frac_forestal, continuidad, peso_modelo,"
            + " frac_sin_combustible, f_pendiente, version_modelo)"
            + " values (?, 0.5, 0.8, 0.7, 0.1, 0.9, 'v1.0')",
        INE);
    jdbc.update(
        "insert into vulnerab_municipio (ine_code, poblacion_norm, frac_espacio_protegido,"
            + " comp_vulnerab, version_modelo) values (?, 0.419, 0.137, 33.44, 'v1.0')",
        INE);

    // Dos días para que serie30d traiga más de un punto.
    for (String fecha : new String[] {"2026-08-15", "2026-08-16"}) {
      jdbc.update(
          "insert into meteo_municipio (ine_code, fecha, temp_12utc_c, hr_12utc_pct,"
              + " viento_12utc_kmh, precip_24h_mm, elevacion_celda_m, delta_altitud_m, source)"
              + " values (?, ?::date, 31.0, 28.0, 12.6, 0.0, 120.0, 132.0, 'test')",
          INE,
          fecha);
      jdbc.update(
          "insert into fwi_municipio (ine_code, fecha, ffmc, dmc, dc, isi, bui, fwi)"
              + " values (?, ?::date, 90.0, 80.0, 500.0, 12.0, 90.0, 30.0)",
          INE,
          fecha);
      jdbc.update(
          "insert into indice_peligro (ine_code, fecha, comp_meteo, comp_estructural, comp_vulnerab,"
              + " indice, nivel, alerta_30_30_30, viento_alineado, version_modelo)"
              + " values (?, ?::date, 60.0, 6.7, 33.44, 32.5, 2, true, null, 'v1.0')",
          INE,
          fecha);
    }
    jdbc.execute("refresh materialized view mv_indice_hoy");
  }

  @Test
  void listaTraeElMunicipioConBanderasYMeta() throws Exception {
    mvc.perform(get("/api/v1/municipios"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[?(@.ineCode=='12126')].nivel").exists())
        .andExpect(jsonPath("$.data[?(@.ineCode=='12126')].banderas.regla303030").exists())
        .andExpect(jsonPath("$.meta.versionModelo").value("v1.0"))
        .andExpect(jsonPath("$.meta.aviso").exists());
  }

  @Test
  void detalleTraeComponentesBanderasYCalidad() throws Exception {
    String body =
        mvc.perform(get("/api/v1/municipios/{ine}", INE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.componentes.meteo").value(60.0))
            .andExpect(jsonPath("$.componentes.estructural").value(6.7))
            .andExpect(jsonPath("$.componentes.vulnerabilidad").value(33.44))
            .andExpect(jsonPath("$.fwi.fwi").value(30.0))
            .andExpect(jsonPath("$.calidadDato.deltaAltitudM").value(132.0))
            .andExpect(jsonPath("$.calidadDato.fracSinCombustible").value(0.1))
            .andExpect(jsonPath("$.serie30d.length()").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
    // vientoAlineado NULL, no false: se comprueba sobre el JSON crudo (jsonPath trata null raro).
    assertTrue(
        body.replace(" ", "").contains("\"vientoAlineado\":null"),
        "vientoAlineado debe ser null explícito, no false ni ausente");
  }

  @Test
  void municipioInexistenteDa404() throws Exception {
    mvc.perform(get("/api/v1/municipios/{ine}", "99999")).andExpect(status().isNotFound());
  }

  @Test
  void metodologiaExponeVersionYCaveats() throws Exception {
    mvc.perform(get("/api/v1/metodologia"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.versionModelo").value("v1.0"))
        .andExpect(jsonPath("$.pesos.estructural").value(0.65))
        .andExpect(jsonPath("$.normaPoblacion").value("sqrt"))
        .andExpect(jsonPath("$.caveats.length()").value(3))
        .andExpect(jsonPath("$.aviso").exists());
  }
}
