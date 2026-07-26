package dev.r0b3r7.guaita;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class GuaitaApiApplicationTests {

  @Autowired private JdbcTemplate jdbc;

  @Test
  void contextLoads() {}

  @Test
  void v1HabilitaPostgis() {
    // Si Flyway no aplicó V1, esta función no existe y la consulta falla.
    String version = jdbc.queryForObject("select postgis_version()", String.class);
    assertNotNull(version, "postgis_version() no devolvió valor: V1 no se aplicó");
    assertTrue(!version.isBlank(), "postgis_version() vacío");
  }

  @Test
  void v1InstalaLasTresExtensiones() {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from pg_extension"
                + " where extname in ('postgis', 'citext', 'pgcrypto')",
            Integer.class);
    assertEquals(3, n, "Faltan extensiones instaladas por V1");
  }
}
