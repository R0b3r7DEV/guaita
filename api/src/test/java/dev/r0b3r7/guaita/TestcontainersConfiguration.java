package dev.r0b3r7.guaita;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

// public: la importan tests de otros paquetes (web.tiles) para compartir el contenedor.
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  // PostGIS, no postgres pelado: V1__extensions.sql hace `create extension postgis`.
  // La imagen postgis es compatible con el driver postgres (asCompatibleSubstituteFor).
  private static final DockerImageName POSTGIS_IMAGE =
      DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres");

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgresContainer() {
    return new PostgreSQLContainer(POSTGIS_IMAGE);
  }
}
