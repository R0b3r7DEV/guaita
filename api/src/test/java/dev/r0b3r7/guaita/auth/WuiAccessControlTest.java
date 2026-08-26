package dev.r0b3r7.guaita.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.r0b3r7.guaita.TestcontainersConfiguration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Cierre de T2 (docs/07): el detalle IUF es sensible. Los siete tests que demuestran que el control
 * de acceso está cerrado (401 sin token, 403 de otro término, agregado público, sin titularidad en
 * el JSON, tesela de detalle protegida, token expirado y manipulado -> 401, no 500).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class WuiAccessControlTest {

  private static final String INE = "12007"; // Alfondeguilla
  private static final String OTRO = "12126"; // otro término

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private JwtEncoder encoder;

  @BeforeEach
  void sembrar() {
    jdbc.update("delete from wui_edificacion where ine_code = ?", INE);
    jdbc.update("delete from edificacion where ine_code = ?", INE);
    jdbc.update("delete from municipio where ine_code = ?", INE);
    jdbc.update(
        "insert into municipio (ine_code, nombre, comarca, geom, superficie_ha, poblacion)"
            + " select ?, 'Alfondeguilla', 'Plana Baixa', g, st_area(g)/1e4, 1000 from ("
            + "  select st_multi(st_buffer("
            + "    st_transform(st_setsrid(st_makepoint(-0.28, 39.86), 4326), 25830), 1500)) g) t",
        INE);
    jdbc.update(
        "insert into edificacion (ref_catastral, ine_code, uso, geom, source, source_url)"
            + " select 'TEST0001', ?, '1_residential', st_multi(st_buffer("
            + "   st_transform(st_setsrid(st_makepoint(-0.28, 39.86), 4326), 25830), 8)),"
            + " 'test', 'test'",
        INE);
    jdbc.update(
        "insert into wui_edificacion (ref_catastral, ine_code, dist_forestal_m,"
            + " area_forestal_en_franja_m2, frac_franja_ocupada, clase, cumple, advertencia_margen,"
            + " franja_m, version_analisis)"
            + " values ('TEST0001', ?, 5.0, 100, 0.1, 'incumple', false, false, 30, 'v2-norma')",
        INE);
  }

  // a) Sin JWT al detalle -> 401.
  @Test
  void detalleSinTokenDa401() throws Exception {
    mvc.perform(get("/api/v1/wui/municipio/{ine}", INE)).andExpect(status().isUnauthorized());
  }

  // b) JWT de otro municipio -> 403.
  @Test
  void detalleDeOtroTerminoDa403() throws Exception {
    mvc.perform(bearer(get("/api/v1/wui/municipio/{ine}", INE), token(OTRO, "tecnico", futuro())))
        .andExpect(status().isForbidden());
  }

  // c) Agregado responde sin JWT.
  @Test
  void agregadoEsPublico() throws Exception {
    mvc.perform(get("/api/v1/wui/agregado/{ine}", INE)).andExpect(status().isOk());
  }

  // d) Ninguna respuesta filtra campos de titularidad (verificado sobre el JSON serializado).
  @Test
  void detalleNoFiltraTitularidad() throws Exception {
    String cuerpo =
        mvc.perform(bearer(get("/api/v1/wui/municipio/{ine}", INE), token(INE, "tecnico", futuro())))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString()
            .toLowerCase(Locale.ROOT);
    assertTrue(cuerpo.contains("refcatastral"), "debe llevar la referencia catastral");
    for (String prohibido :
        new String[] {"titular", "propietario", "direccion", "dirección", "domicilio", "nif", "dni",
          "apellido"}) {
      assertFalse(cuerpo.contains(prohibido), "no debe filtrar '" + prohibido + "'");
    }
  }

  // e) Tesela de detalle a z<14 sin JWT no devuelve geometría (401, sin cuerpo).
  @Test
  void teselaDetalleSinTokenNoDaGeometria() throws Exception {
    mvc.perform(get("/api/v1/tiles/wui/{z}/{x}/{y}.mvt", 10, 500, 380))
        .andExpect(status().isUnauthorized());
  }

  // f) Token expirado -> 401 (no 500).
  @Test
  void tokenExpiradoDa401() throws Exception {
    String expirado = token(INE, "tecnico", Instant.now().minusSeconds(60));
    mvc.perform(bearer(get("/api/v1/wui/municipio/{ine}", INE), expirado))
        .andExpect(status().isUnauthorized());
  }

  // g) Token con firma manipulada -> 401.
  @Test
  void tokenManipuladoDa401() throws Exception {
    String valido = token(INE, "tecnico", futuro());
    char ultimo = valido.charAt(valido.length() - 1);
    String manipulado = valido.substring(0, valido.length() - 1) + (ultimo == 'A' ? 'B' : 'A');
    mvc.perform(bearer(get("/api/v1/wui/municipio/{ine}", INE), manipulado))
        .andExpect(status().isUnauthorized());
  }

  // --- utilidades ---

  private static Instant futuro() {
    return Instant.now().plusSeconds(900);
  }

  private String token(String ine, String rol, Instant expira) {
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer("guaita")
            .subject(UUID.randomUUID().toString())
            .issuedAt(Instant.now().minusSeconds(120))
            .expiresAt(expira)
            .claim("ine", ine)
            .claim("rol", rol)
            .build();
    return encoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder bearer(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String tok) {
    return req.header("Authorization", "Bearer " + tok);
  }
}
