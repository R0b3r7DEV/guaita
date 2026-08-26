package dev.r0b3r7.guaita.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Rate limit en memoria (ventana fija por clave), suficiente para un solo nodo (docs/07). El login
 * es el objetivo obvio de fuerza bruta; el detalle IUF también se limita. No sustituye al limit_req
 * de nginx: es una segunda barrera a nivel de aplicación.
 */
@Component
public class RateLimiter {

  private record Ventana(Instant inicio, int cuenta) {}

  private final Map<String, Ventana> ventanas = new ConcurrentHashMap<>();

  /** true si la petición se PERMITE; false si se supera el límite en la ventana. */
  public boolean permite(String clave, int max, Duration ventana) {
    Instant ahora = Instant.now();
    Ventana v =
        ventanas.compute(
            clave,
            (k, actual) -> {
              if (actual == null || actual.inicio().plus(ventana).isBefore(ahora)) {
                return new Ventana(ahora, 1);
              }
              return new Ventana(actual.inicio(), actual.cuenta() + 1);
            });
    return v.cuenta() <= max;
  }
}
