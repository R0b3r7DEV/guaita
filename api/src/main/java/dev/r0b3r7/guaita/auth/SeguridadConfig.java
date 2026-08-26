package dev.r0b3r7.guaita.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Seguridad del control de acceso IUF (T2, docs/07). Stateless (JWT), sin sesión de servidor. El
 * SECRETO del JWT NO tiene valor por defecto: un entorno mal configurado FALLA al arrancar. CORS
 * restringido al dominio de producción, sin comodín. CSRF desactivado porque no hay sesión de
 * servidor y el refresh viaja en cookie SameSite=Strict (un POST cross-site no la envía).
 */
@Configuration
@EnableWebSecurity
class SeguridadConfig {

  // Sin default: HS256 exige >= 256 bits; el arranque falla si falta o es corto (deseado).
  @Value("${guaita.auth.jwt-secret}")
  private String jwtSecret;

  @Value("${guaita.auth.cors-origin:https://guaita.xpl0day.com}")
  private String corsOrigin;

  @Bean
  SecretKey jwtKey() {
    byte[] bytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < 32) {
      throw new IllegalStateException("guaita.auth.jwt-secret debe tener >= 32 bytes (HS256)");
    }
    return new SecretKeySpec(bytes, "HmacSHA256");
  }

  @Bean
  JwtEncoder jwtEncoder(SecretKey key) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(key));
  }

  @Bean
  JwtDecoder jwtDecoder(SecretKey key) {
    return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
  }

  /** Argon2id, perfil OWASP: m=19 MiB, t=2, p=1, salt 16 B, hash 32 B. */
  @Bean
  PasswordEncoder passwordEncoder() {
    return new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
  }

  @Bean
  SecurityFilterChain filtros(HttpSecurity http, JwtDecoder decoder, ProblemAuthHandlers problemas)
      throws Exception {
    http.csrf(csrf -> csrf.disable())
        .cors(cors -> cors.configurationSource(corsSource()))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            reg ->
                reg.requestMatchers("/actuator/health/**")
                    .permitAll()
                    .requestMatchers(
                        "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/municipios/**",
                        "/api/v1/mapa/**",
                        "/api/v1/metodologia",
                        "/api/v1/tiles/municipios/**",
                        "/api/v1/wui/agregado/**")
                    .permitAll()
                    .requestMatchers("/api/v1/wui/**", "/api/v1/tiles/wui/**")
                    .authenticated()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .jwt(jwt -> jwt.decoder(decoder))
                    .authenticationEntryPoint(problemas))
        .exceptionHandling(
            ex -> ex.authenticationEntryPoint(problemas).accessDeniedHandler(problemas));
    return http.build();
  }

  private CorsConfigurationSource corsSource() {
    CorsConfiguration cfg = new CorsConfiguration();
    cfg.setAllowedOrigins(List.of(corsOrigin)); // dominio de producción, sin comodín
    cfg.setAllowedMethods(List.of("GET", "POST"));
    cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "If-None-Match"));
    cfg.setAllowCredentials(true); // el refresh viaja en cookie
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", cfg);
    return source;
  }
}
