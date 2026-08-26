package dev.r0b3r7.guaita.auth;

import java.time.Duration;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Emite el ACCESS token (JWT HS256, 15 min). El refresh es opaco (ver {@link
 * RefreshTokenServicio}).
 */
@Service
public class TokenService {

  static final Duration ACCESS_TTL = Duration.ofMinutes(15);

  private final JwtEncoder encoder;

  TokenService(JwtEncoder encoder) {
    this.encoder = encoder;
  }

  /** Access token con el término autorizado ({@code ine}) y el rol como claims. */
  String access(Usuario u) {
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer("guaita")
            .issuedAt(now)
            .expiresAt(now.plus(ACCESS_TTL))
            .subject(u.id().toString())
            .claim("email", u.email())
            .claim("ine", u.ineCode() == null ? "" : u.ineCode())
            .claim("rol", u.rol())
            .build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }
}
