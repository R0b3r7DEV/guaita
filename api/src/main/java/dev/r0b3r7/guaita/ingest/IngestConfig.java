package dev.r0b3r7.guaita.ingest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans del módulo de ingesta. La base-url de Open-Meteo es configurable (tests / entornos). */
@Configuration
class IngestConfig {

  @Bean
  OpenMeteoClient openMeteoClient(
      @Value("${guaita.openmeteo.base-url:https://archive-api.open-meteo.com/v1/archive}")
          String baseUrl) {
    return new OpenMeteoClient(baseUrl);
  }
}
