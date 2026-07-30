package dev.r0b3r7.guaita.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test de la capa HTTP APARTE, con un servidor local (JDK {@code HttpServer}) que sirve el fixture.
 * Verifica el camino fetch → parse → map de punta a punta sin salir a la red, y el reintento con
 * backoff ante errores reintentables.
 */
class OpenMeteoClientTest {

  private static final OpenMeteoClient.Punto MORELLA =
      new OpenMeteoClient.Punto("12080", -0.101, 40.619);

  private HttpServer server;
  private String baseUrl;
  private final AtomicInteger hits = new AtomicInteger();
  private volatile int failFirst = 0;
  private byte[] fixture;

  @BeforeEach
  void start() throws IOException {
    try (InputStream in =
        OpenMeteoClientTest.class.getResourceAsStream("/openmeteo/archive-morella-2023-08.json")) {
      fixture = in.readAllBytes();
    }
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/archive",
        ex -> {
          if (hits.incrementAndGet() <= failFirst) {
            ex.sendResponseHeaders(503, -1);
            ex.close();
            return;
          }
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(200, fixture.length);
          try (OutputStream os = ex.getResponseBody()) {
            os.write(fixture);
          }
        });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/archive";
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void descargaYMapeaElFixturePorHttp() {
    OpenMeteoClient client = new OpenMeteoClient(baseUrl);
    List<MeteoMunicipio> rows =
        client.fetch(
            List.of(MORELLA), LocalDate.parse("2023-08-02"), LocalDate.parse("2023-08-31"));

    assertEquals(30, rows.size());
    MeteoMunicipio d14 =
        rows.stream()
            .filter(r -> r.fecha().equals(LocalDate.parse("2023-08-14")))
            .findFirst()
            .orElseThrow();
    assertEquals(27.7, d14.temp12utcC(), 1e-9);
    assertEquals("12080", d14.ineCode());
    assertTrue(d14.sourceUrl().contains("era5_seamless"), "la URL de procedencia fija el modelo");
    assertTrue(d14.sourceUrl().contains("wind_speed_unit=kmh"), "y fija las unidades de viento");
  }

  @Test
  void reintentaTrasErroresReintentablesYAcabaBien() {
    failFirst = 2; // 503, 503, luego 200
    OpenMeteoClient client =
        new OpenMeteoClient(
            baseUrl,
            HttpClient.newHttpClient(),
            new ObjectMapper(),
            new OpenMeteoMapper(),
            Duration.ofMillis(5));
    List<MeteoMunicipio> rows =
        client.fetch(
            List.of(MORELLA), LocalDate.parse("2023-08-02"), LocalDate.parse("2023-08-31"));

    assertEquals(30, rows.size());
    assertEquals(3, hits.get(), "debió reintentar hasta el tercer intento");
  }
}
