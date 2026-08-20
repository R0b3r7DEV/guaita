package dev.r0b3r7.guaita;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class GuaitaApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(GuaitaApiApplication.class, args);
  }
}
