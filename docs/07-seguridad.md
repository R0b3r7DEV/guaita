# 07 · Seguridad y modelo de amenazas

El autor viene de perfil de ciberseguridad. Este documento no es un trámite: es
la parte del proyecto que mejor demuestra criterio.

## Regla cero

**Ninguna clave de API sale del backend. Nunca.**

Las claves de NASA FIRMS y AEMET OpenData viven en variables de entorno del
contenedor `api`. El frontend no las conoce, no las recibe y no puede
derivarlas. Si el navegador necesita algo de una fuente externa, se hace proxy
desde Spring con caché.

Este patrón ya se aplicó en XPL0DAY tras una exposición de clave. Aquí se aplica
desde el día uno.

## Modelo de amenazas

| # | Amenaza | Vector | Impacto | Mitigación |
|---|---|---|---|---|
| T1 | Fuga de claves de terceros | Clave en bundle JS, en repo, o en logs | Cuota agotada, suspensión de cuenta | Solo backend; `.env` fuera de git; filtro de logs que redacta patrones de clave; secret scanning en CI |
| T2 | Exposición de datos de IUF | Endpoint de detalle sin auth | Mapa público de viviendas vulnerables — riesgo real para terceros | Detalle solo con JWT; agregado público; sin titulares en BD |
| T3 | Inyección SQL en consultas espaciales | Concatenación de bbox o `ine_code` | Lectura/escritura arbitraria | Consultas parametrizadas siempre; validar bbox con Zod/Bean Validation antes de tocar SQL |
| T4 | Abuso del endpoint de tiles | Peticiones masivas a z alto | DoS por CPU en PostGIS | Rate limit por IP; z máximo 16; caché de tiles; `statement_timeout` en BD |
| T5 | Enumeración de correos | Respuestas distintas según exista el email | Fuga de padrón de suscriptores | 202 uniforme; timing constante; sin mensajes diferenciales |
| T6 | Envenenamiento de datos | Fuente externa comprometida o cambia formato | Índices erróneos publicados | Validación de esquema en ingesta; rangos plausibles (temp −20..50, HR 0..100); rechazar lote entero si falla y alertar |
| T7 | Suplantación de autoridad | Alguien toma el índice por oficial | Decisión operativa equivocada en emergencia | Aviso permanente en UI y en `meta` de la API; nunca lenguaje imperativo de emergencia |
| T8 | Dependencias vulnerables | Cadena de suministro | RCE | Dependabot; `gradle dependencyCheck`; `npm audit` en CI que rompe el build en crítica |

**T7 es la amenaza más seria del proyecto** y no es técnica. Si alguien confía en
GUAITA en lugar del 112 durante un incendio, el daño es real. De ahí que el
descargo no sea una nota al pie sino un elemento persistente de la interfaz.

## Autenticación

- JWT firmado (HS256 con secreto largo, o RS256 si se separa el emisor).
- Access token 15 min, refresh 7 días con rotación y detección de reutilización.
- Refresh en cookie `HttpOnly; Secure; SameSite=Strict`. Access en memoria del
  cliente, **nunca** en `localStorage`.
- Contraseñas con Argon2id. Nada de BCrypt nuevo en 2026.

### Decisión: sin auto-registro (cuentas creadas por administrador)

No habrá registro público ni recuperación de contraseña. Son 135 municipios, un
conjunto **cerrado y pequeño**: las cuentas de técnico municipal las crea un
administrador manualmente. Esto elimina de la Fase 5 todo el flujo de alta,
verificación de correo y recuperación de contraseña — superficie de ataque que no
hace falta mantener.

Modelo de usuario mínimo (`usuario`, V18):

| Campo | Notas |
|---|---|
| `id` | uuid |
| `email` | citext, único |
| `password_hash` | Argon2id |
| `ine_code` | municipio autorizado (NULL para `admin`, que ve todos) |
| `rol` | `tecnico` \| `admin` |

**Implementado (Fase 5).** Detalles:

- **Argon2id** perfil OWASP: memoria 19 MiB (`m=19456`), `t=2`, `p=1`, salt 16 B, hash
  32 B. Equilibra resistencia a GPU con un login que no ahoga el hilo en un VPS
  compartido con XPL0DAY.
- **Access token** JWT HS256, 15 min, con claims `ine` y `rol`; el cliente lo guarda
  EN MEMORIA. **Refresh** opaco (no JWT), 7 días, en cookie `HttpOnly; Secure;
  SameSite=Strict`, con **rotación** y **detección de reutilización**: se guarda el
  SHA-256 del token; cada refresh emite uno nuevo en la misma familia y marca el
  anterior usado; si llega un token ya usado, es robo → se revoca la familia entera.
- **Secreto JWT sin default** (`GUAITA_AUTH_JWT_SECRET`, ≥32 B): el arranque falla si
  falta, no usa una clave conocida.
- **CSRF desactivado** con fundamento: no hay sesión de servidor (stateless) y el
  refresh viaja en cookie `SameSite=Strict`, que un POST cross-site no envía.
- **Login sin enumeración:** mismo 401 y trabajo (un verify Argon2 siempre, exista o
  no el email) para email o contraseña incorrectos. Rate limit 5/h por IP.

**Alta de cuentas por administrador** (no hay auto-registro; runner gatillado):

```
docker compose run --rm \
  -e SPRING_APPLICATION_JSON='{"guaita.auth.crear.run":true,
     "guaita.auth.crear.email":"tecnico@alfondeguilla.es",
     "guaita.auth.crear.password":"...",
     "guaita.auth.crear.ine":"12007","guaita.auth.crear.rol":"tecnico"}' api
```

## Cabeceras

```
Content-Security-Policy: default-src 'self'; img-src 'self' data: blob:;
  connect-src 'self'; worker-src 'self' blob:; style-src 'self' 'unsafe-inline'
Strict-Transport-Security: max-age=31536000; includeSubDomains
X-Content-Type-Options: nosniff
Referrer-Policy: strict-origin-when-cross-origin
Permissions-Policy: geolocation=(self), camera=(), microphone=()
```

`worker-src blob:` y `img-src blob:` son necesarios para MapLibre. `unsafe-inline`
en estilos también lo pide; documentado como excepción consciente, no por
descuido.

CORS restringido al dominio de producción. Sin comodín.

## Rate limiting

Bucket4j sobre Redis o, si se quiere evitar la dependencia, en memoria (un solo
nodo, es suficiente).

| Endpoint | Límite |
|---|---|
| Lectura pública | 120 req/min/IP |
| Tiles | 600 req/min/IP |
| `POST /suscripciones` | 5 req/hora/IP |
| Informe PDF | 10 req/hora/usuario |

## RGPD

- **Dato personal tratado: únicamente el correo de suscripción.** Base jurídica:
  consentimiento explícito con doble opt-in.
- Baja en un clic sin necesidad de iniciar sesión.
- Retención: se borra la suscripción a los 24 meses sin actividad.
- Los datos catastrales tratados no son personales al excluirse la titularidad;
  documentar el razonamiento por si acaso.
- Aviso de privacidad y política de cookies. Sin analítica de terceros; si se
  quiere métrica, Plausible o Umami autoalojados.

## Secretos y CI

- `.env` en `.gitignore` desde el primer commit, y `.env.example` versionado.
- Secretos de producción en el gestor del VPS, no en el repo.
- **Ninguna credencial tiene valor por defecto en configuración versionada. Un
  entorno incompleto debe fallar en el arranque, no degradarse a una credencial
  conocida.**
- Pipeline: `gitleaks` → build → tests → `dependencyCheck` → `npm audit`.
- Rotación de la clave FIRMS/AEMET documentada en un runbook.

## Auditoría

Log estructurado (JSON) de: accesos a IUF detallado, generación de informes,
altas y bajas de suscripción, fallos de ingesta. Sin PII en los logs — el correo
se registra hasheado.
