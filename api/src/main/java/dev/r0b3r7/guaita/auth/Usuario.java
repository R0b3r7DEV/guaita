package dev.r0b3r7.guaita.auth;

import java.util.UUID;

/**
 * Usuario técnico municipal o administrador (docs/07). Sin auto-registro: las cuentas las crea un
 * administrador. {@code ineCode} es el término autorizado (NULL para admin, que ve todos). Nunca se
 * expone en la API: solo alimenta el control de acceso al detalle IUF.
 */
public record Usuario(UUID id, String email, String passwordHash, String ineCode, String rol) {

  boolean esAdmin() {
    return "admin".equals(rol);
  }

  /** ¿Puede ver el detalle de {@code ine}? Admin ve todos; el técnico solo SU término. */
  boolean puedeVer(String ine) {
    return esAdmin() || (ineCode != null && ineCode.equals(ine));
  }
}
