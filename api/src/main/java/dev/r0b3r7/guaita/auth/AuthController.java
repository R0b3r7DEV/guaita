package dev.r0b3r7.guaita.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Autenticación (docs/07). Sin auto-registro. Access token (15 min) en el cuerpo (el cliente lo
 * guarda EN MEMORIA, nunca en localStorage); refresh (7 días, rotación) en cookie HttpOnly + Secure
 * + SameSite=Strict. El login NO permite enumerar cuentas: misma respuesta y trabajo (un verify
 * Argon2 siempre, exista o no el email) y mismo 401 para email o contraseña incorrectos.
 */
@RestController
class AuthController {

  private static final String COOKIE = "guaita_refresh";
  private static final int LOGIN_MAX = 5; // por hora e IP (docs/07)

  private final UsuarioRepositorio usuarios;
  private final PasswordEncoder encoder;
  private final TokenService tokens;
  private final RefreshTokenServicio refresh;
  private final RateLimiter rateLimiter;
  private final String hashSeñuelo; // para igualar el tiempo cuando el email no existe

  AuthController(
      UsuarioRepositorio usuarios,
      PasswordEncoder encoder,
      TokenService tokens,
      RefreshTokenServicio refresh,
      RateLimiter rateLimiter) {
    this.usuarios = usuarios;
    this.encoder = encoder;
    this.tokens = tokens;
    this.refresh = refresh;
    this.rateLimiter = rateLimiter;
    this.hashSeñuelo = encoder.encode("señuelo-anti-enumeracion");
  }

  record LoginReq(String email, String password) {}

  record LoginResp(String accessToken, long expiraEnSeg, String ineCode, String rol) {}

  @PostMapping("/api/v1/auth/login")
  ResponseEntity<?> login(@RequestBody LoginReq req, HttpServletRequest http) {
    if (!rateLimiter.permite("login:" + ip(http), LOGIN_MAX, Duration.ofHours(1))) {
      return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
    }
    String email = req.email() == null ? "" : req.email().strip();
    String pass = req.password() == null ? "" : req.password();
    Optional<Usuario> u = usuarios.porEmail(email);
    // Se verifica SIEMPRE (contra el hash real o el señuelo) para no filtrar por tiempo si existe.
    boolean ok = encoder.matches(pass, u.map(Usuario::passwordHash).orElse(hashSeñuelo));
    if (u.isEmpty() || !ok) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build(); // 401 genérico, sin distinguir
    }
    Usuario usuario = u.get();
    String refreshTok = refresh.nuevaFamilia(usuario.id());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie(refreshTok).toString())
        .body(
            new LoginResp(
                tokens.access(usuario),
                TokenService.ACCESS_TTL.toSeconds(),
                usuario.ineCode(),
                usuario.rol()));
  }

  @PostMapping("/api/v1/auth/refresh")
  ResponseEntity<?> refrescar(@CookieValue(name = COOKIE, required = false) String tok) {
    if (tok == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    Optional<RefreshTokenServicio.Emitido> e = refresh.rotar(tok, usuarios);
    if (e.isEmpty()) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .header(HttpHeaders.SET_COOKIE, cookieBorrado().toString())
          .build();
    }
    Usuario usuario = e.get().usuario();
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie(e.get().token()).toString())
        .body(
            new LoginResp(
                tokens.access(usuario),
                TokenService.ACCESS_TTL.toSeconds(),
                usuario.ineCode(),
                usuario.rol()));
  }

  @PostMapping("/api/v1/auth/logout")
  ResponseEntity<Void> logout(@CookieValue(name = COOKIE, required = false) String tok) {
    if (tok != null) {
      refresh.revocarFamilia(tok);
    }
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookieBorrado().toString())
        .build();
  }

  private static ResponseCookie cookie(String valor) {
    return ResponseCookie.from(COOKIE, valor)
        .httpOnly(true)
        .secure(true)
        .sameSite("Strict")
        .path("/api/v1/auth")
        .maxAge(RefreshTokenServicio.REFRESH_TTL)
        .build();
  }

  private static ResponseCookie cookieBorrado() {
    return ResponseCookie.from(COOKIE, "")
        .httpOnly(true)
        .secure(true)
        .sameSite("Strict")
        .path("/api/v1/auth")
        .maxAge(0)
        .build();
  }

  private static String ip(HttpServletRequest req) {
    String xff = req.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) {
      return xff.split(",")[0].strip();
    }
    return req.getRemoteAddr();
  }
}
