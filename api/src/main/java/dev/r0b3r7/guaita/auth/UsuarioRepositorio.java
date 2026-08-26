package dev.r0b3r7.guaita.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Acceso a {@code usuario} (docs/07). Solo lo que necesita el control de acceso. */
@Repository
class UsuarioRepositorio {

  private final JdbcTemplate jdbc;

  UsuarioRepositorio(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  Optional<Usuario> porEmail(String email) {
    return jdbc
        .query(
            "select id, email, password_hash, ine_code, rol from usuario where email = ?",
            (rs, n) ->
                new Usuario(
                    (UUID) rs.getObject("id"),
                    rs.getString("email"),
                    rs.getString("password_hash"),
                    rs.getString("ine_code"),
                    rs.getString("rol")),
            email)
        .stream()
        .findFirst();
  }

  Optional<Usuario> porId(UUID id) {
    return jdbc
        .query(
            "select id, email, password_hash, ine_code, rol from usuario where id = ?",
            (rs, n) ->
                new Usuario(
                    (UUID) rs.getObject("id"),
                    rs.getString("email"),
                    rs.getString("password_hash"),
                    rs.getString("ine_code"),
                    rs.getString("rol")),
            id)
        .stream()
        .findFirst();
  }

  /** Alta/actualización idempotente por email (la usa el runner de administración). */
  void guardar(String email, String hash, String ineCode, String rol) {
    jdbc.update(
        "insert into usuario (email, password_hash, ine_code, rol) values (?, ?, ?, ?)"
            + " on conflict (email) do update set password_hash = excluded.password_hash,"
            + " ine_code = excluded.ine_code, rol = excluded.rol",
        email,
        hash,
        ineCode,
        rol);
  }
}
