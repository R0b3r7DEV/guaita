package dev.r0b3r7.guaita.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Alta de cuentas por ADMINISTRADOR (docs/07): no hay auto-registro. Gatillado por {@code
 * guaita.auth.crear.run=true}; hashea la contraseña con Argon2id y hace upsert por email, luego sale.
 * Procedimiento (VPS):
 *
 * <pre>
 * docker compose run --rm \
 *   -e SPRING_APPLICATION_JSON='{"guaita.auth.crear.run":true,
 *      "guaita.auth.crear.email":"tecnico@alfondeguilla.es",
 *      "guaita.auth.crear.password":"...",
 *      "guaita.auth.crear.ine":"12007","guaita.auth.crear.rol":"tecnico"}' api
 * </pre>
 *
 * El rol admin lleva ine vacío (ve todos); el técnico exige ine (su término).
 */
@Component
class AdminUsuarioRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(AdminUsuarioRunner.class);

  private final UsuarioRepositorio usuarios;
  private final PasswordEncoder encoder;
  private final boolean run;
  private final String email;
  private final String password;
  private final String ine;
  private final String rol;

  AdminUsuarioRunner(
      UsuarioRepositorio usuarios,
      PasswordEncoder encoder,
      @Value("${guaita.auth.crear.run:false}") boolean run,
      @Value("${guaita.auth.crear.email:}") String email,
      @Value("${guaita.auth.crear.password:}") String password,
      @Value("${guaita.auth.crear.ine:}") String ine,
      @Value("${guaita.auth.crear.rol:tecnico}") String rol) {
    this.usuarios = usuarios;
    this.encoder = encoder;
    this.run = run;
    this.email = email;
    this.password = password;
    this.ine = ine;
    this.rol = rol;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!run) {
      return;
    }
    if (email.isBlank() || password.isBlank()) {
      throw new IllegalStateException("faltan guaita.auth.crear.email/password");
    }
    String ineCode = "admin".equals(rol) ? null : (ine.isBlank() ? null : ine);
    if (!"admin".equals(rol) && ineCode == null) {
      throw new IllegalStateException("un técnico necesita guaita.auth.crear.ine (su término)");
    }
    usuarios.guardar(email, encoder.encode(password), ineCode, rol);
    log.info("usuario {} ({}, ine={}) creado/actualizado", email, rol, ineCode);
    System.exit(0);
  }
}
