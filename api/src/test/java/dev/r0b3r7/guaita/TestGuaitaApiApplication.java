package dev.r0b3r7.guaita;

import org.springframework.boot.SpringApplication;

public class TestGuaitaApiApplication {

  public static void main(String[] args) {
    SpringApplication.from(GuaitaApiApplication::main)
        .with(TestcontainersConfiguration.class)
        .run(args);
  }
}
