package dev.r0b3r7.guaita.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh tokens OPACOS (no JWT) con rotación y detección de reutilización (docs/07). Se guarda el
 * SHA-256 del token, no el token. Cada refresh emite uno nuevo en la misma familia y marca el
 * anterior como usado; si llega un token ya usado, es robo -> se revoca la familia entera.
 */
@Service
public class RefreshTokenServicio {

  static final Duration REFRESH_TTL = Duration.ofDays(7);
  private static final SecureRandom RNG = new SecureRandom();

  private final JdbcTemplate jdbc;

  RefreshTokenServicio(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Resultado de emitir/rotar: el token en claro (va al cliente) y el usuario. */
  public record Emitido(String token, Usuario usuario) {}

  /** Crea un refresh nuevo (familia nueva) para un usuario recién autenticado. */
  @Transactional
  String nuevaFamilia(UUID usuarioId) {
    String token = generar();
    jdbc.update(
        "insert into refresh_token (usuario_id, token_hash, familia, expira_en)"
            + " values (?, ?, ?, ?)",
        usuarioId,
        hash(token),
        UUID.randomUUID(),
        java.sql.Timestamp.from(Instant.now().plus(REFRESH_TTL)));
    return token;
  }

  /**
   * Rota un refresh: valida, detecta reutilización y emite el siguiente. Devuelve vacío si el token
   * es inválido/expirado/revocado (el controlador responde 401).
   */
  @Transactional
  Optional<Emitido> rotar(String tokenPresentado, UsuarioRepositorio usuarios) {
    var filas =
        jdbc.queryForList(
            "select id, usuario_id, familia, expira_en, usado, revocado"
                + " from refresh_token where token_hash = ?",
            hash(tokenPresentado));
    if (filas.isEmpty()) {
      return Optional.empty(); // token desconocido
    }
    var f = filas.get(0);
    UUID familia = (UUID) f.get("familia");
    boolean usado = (boolean) f.get("usado");
    boolean revocado = (boolean) f.get("revocado");
    Instant expira = ((java.sql.Timestamp) f.get("expira_en")).toInstant();

    if (revocado || expira.isBefore(Instant.now())) {
      return Optional.empty();
    }
    if (usado) {
      // REUTILIZACIÓN de un token ya rotado -> robo. Se revoca la familia entera.
      jdbc.update("update refresh_token set revocado = true where familia = ?", familia);
      return Optional.empty();
    }
    UUID usuarioId = (UUID) f.get("usuario_id");
    jdbc.update("update refresh_token set usado = true where id = ?", (UUID) f.get("id"));
    String siguiente = generar();
    jdbc.update(
        "insert into refresh_token (usuario_id, token_hash, familia, expira_en)"
            + " values (?, ?, ?, ?)",
        usuarioId,
        hash(siguiente),
        familia,
        java.sql.Timestamp.from(Instant.now().plus(REFRESH_TTL)));
    return usuarios.porId(usuarioId).map(u -> new Emitido(siguiente, u));
  }

  /** Cierre de sesión: revoca la familia del token presentado (si existe). */
  @Transactional
  void revocarFamilia(String tokenPresentado) {
    jdbc.update(
        "update refresh_token set revocado = true where familia ="
            + " (select familia from refresh_token where token_hash = ?)",
        hash(tokenPresentado));
  }

  private static String generar() {
    byte[] b = new byte[32];
    RNG.nextBytes(b);
    return HexFormat.of().formatHex(b);
  }

  private static String hash(String token) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 no disponible", e);
    }
  }
}
