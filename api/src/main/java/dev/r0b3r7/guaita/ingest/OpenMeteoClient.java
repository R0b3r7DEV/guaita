package dev.r0b3r7.guaita.ingest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Cliente HTTP de Open-Meteo {@code /v1/archive} (ADR-07), encima del mapper puro. Fija en la
 * petición TODO lo que produciría error silencioso (ver {@link OpenMeteoMapper}): modelo
 * ERA5-Seamless, viento en km/h, {@code timezone=GMT} (para que la hora 12 sea 12:00 UTC).
 * Multi-coordenada: una sola petición cubre muchos municipios. Reintentos con backoff exponencial +
 * jitter en fallos de red, 5xx y 429; los 4xx (salvo 429) NO se reintentan.
 */
public final class OpenMeteoClient {

  /** Un municipio y su punto de consulta (lon/lat WGS84; ST_PointOnSurface del continente). */
  public record Punto(String ineCode, double lon, double lat) {}

  private static final String HOURLY =
      "temperature_2m,relative_humidity_2m,wind_speed_10m,precipitation";
  private static final String USER_AGENT = "guaita/0.1 (+https://github.com/R0b3r7DEV/guaita)";
  private static final int MAX_ATTEMPTS = 4;

  private final String baseUrl;
  private final HttpClient http;
  private final ObjectMapper json;
  private final OpenMeteoMapper mapper;
  private final Duration baseBackoff;

  public OpenMeteoClient(String baseUrl) {
    this(
        baseUrl,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
        new ObjectMapper(),
        new OpenMeteoMapper(),
        Duration.ofMillis(500));
  }

  OpenMeteoClient(
      String baseUrl,
      HttpClient http,
      ObjectMapper json,
      OpenMeteoMapper mapper,
      Duration baseBackoff) {
    this.baseUrl = baseUrl;
    this.http = http;
    this.json = json;
    this.mapper = mapper;
    this.baseBackoff = baseBackoff;
  }

  /**
   * Descarga y mapea la meteo diaria de {@code [start, end]} para los puntos dados. Pide un día
   * extra por delante ({@code start-1}) para cerrar la ventana de lluvia de 24 h del primer día.
   */
  public List<MeteoMunicipio> fetch(List<Punto> puntos, LocalDate start, LocalDate end) {
    if (puntos.isEmpty()) {
      return List.of();
    }
    String url = buildUrl(puntos, start.minusDays(1), end);
    List<OpenMeteoArchive> locs = parse(get(url));
    if (locs.size() != puntos.size()) {
      throw new IllegalStateException(
          "Open-Meteo devolvió " + locs.size() + " localizaciones, esperaba " + puntos.size());
    }
    List<MeteoMunicipio> out = new ArrayList<>();
    for (int i = 0; i < puntos.size(); i++) {
      out.addAll(mapper.map(puntos.get(i).ineCode(), url, locs.get(i)));
    }
    return out;
  }

  private String buildUrl(List<Punto> puntos, LocalDate start, LocalDate end) {
    StringJoiner lat = new StringJoiner(",");
    StringJoiner lon = new StringJoiner(",");
    for (Punto p : puntos) {
      lat.add(fmt(p.lat()));
      lon.add(fmt(p.lon()));
    }
    return baseUrl
        + "?latitude=" + lat
        + "&longitude=" + lon
        + "&start_date=" + start
        + "&end_date=" + end
        + "&hourly=" + HOURLY
        + "&models=era5_seamless"
        + "&wind_speed_unit=kmh"
        + "&timezone=GMT";
  }

  private static String fmt(double v) {
    return String.format(Locale.ROOT, "%.4f", v);
  }

  private List<OpenMeteoArchive> parse(String body) {
    try {
      String t = body.stripLeading();
      if (!t.isEmpty() && t.charAt(0) == '[') {
        return json.readValue(body, new TypeReference<List<OpenMeteoArchive>>() {});
      }
      return List.of(json.readValue(body, OpenMeteoArchive.class));
    } catch (IOException e) {
      throw new IllegalStateException("respuesta de Open-Meteo no parseable", e);
    }
  }

  private String get(String url) {
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build();
    IllegalStateException last = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        int sc = res.statusCode();
        if (sc == 200) {
          return res.body();
        }
        if (sc != 429 && sc < 500) {
          throw new IllegalStateException("Open-Meteo respondió " + sc + ": " + brief(res.body()));
        }
        last = new IllegalStateException("Open-Meteo " + sc + " (reintentable)");
      } catch (IOException e) {
        last = new IllegalStateException("fallo de red a Open-Meteo", e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrumpido esperando a Open-Meteo", e);
      }
      if (attempt < MAX_ATTEMPTS) {
        backoff(attempt);
      }
    }
    throw last;
  }

  private void backoff(int attempt) {
    long base = baseBackoff.toMillis() * (1L << (attempt - 1));
    long jitter = ThreadLocalRandom.current().nextLong(base / 2 + 1);
    try {
      Thread.sleep(base + jitter);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String brief(String body) {
    return body == null ? "" : body.substring(0, Math.min(200, body.length()));
  }
}
