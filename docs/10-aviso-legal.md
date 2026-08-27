# 10 · Aviso legal y privacidad

## Naturaleza del servicio

GUAITA es una **herramienta analítica y de portafolio**, no un servicio oficial.
**No es un sistema de emergencias:** no sustituye al 112, al boletín de
preemergencia por incendios de la Generalitat Valenciana (112 CV / PREVIFOC) ni a
ninguna resolución de la autoridad competente. El índice es una estimación sobre
datos abiertos con **~5 días de desfase** (reanálisis meteorológico); no describe el
riesgo del momento. Ante un incendio, llame al **112**.

Titular: proyecto personal de Ionut Robert Fireteanu (R0b3r7DEV). Código bajo
licencia MIT; los datos, con las atribuciones de la sección correspondiente.

## Protección de datos (RGPD / LOPDGDD)

**La aplicación NO trata datos personales de las personas que la visitan.** El visor
y los endpoints públicos (índice, mapa, agregados de interfaz urbano-forestal) no
requieren registro, no piden ningún dato y no usan analítica de terceros ni cookies
de seguimiento. El detalle IUF por edificación se sirve **solo con geometría y
referencia catastral, nunca titularidad ni direcciones** (ver docs/05 y docs/07).

Datos personales que sí se tratan, mínimos y declarados:

| Dato | Cuándo | Base jurídica | Retención |
|---|---|---|---|
| **Dirección IP** en los logs del proxy (nginx) | En cada petición | Interés legítimo (seguridad y diagnóstico del servicio, art. 6.1.f) | Rotación limitada de logs; sin conservación prolongada ni cesión a terceros. La aplicación NO persiste IPs (el rate limit las usa solo en memoria) |
| **Cuenta de técnico municipal** (email, hash Argon2id de contraseña, término autorizado, rol) | Solo si un administrador la crea | Gestión de la relación con el técnico autorizado | Mientras la cuenta esté activa; **no hay auto-registro** |

- **Cookies:** la única es el *refresh token* (`HttpOnly; Secure; SameSite=Strict`)
  del control de acceso, **estrictamente necesaria** y solo para técnicos
  autenticados → exenta de consentimiento. No hay cookies analíticas ni publicitarias.
- **Encargados del tratamiento:** ninguno. No se envían datos de usuarios a terceros
  (no hay correo de suscripción; las alertas están diferidas, ver docs/08).
- **Logs sin PII adicional:** los logs de aplicación no registran contraseñas ni
  correos en claro (docs/07). Las IPs viven en el log de acceso del proxy, con
  rotación, y no se cruzan con ningún otro dato.
- **Derechos:** acceso, rectificación, supresión, oposición y portabilidad, ante el
  titular (issues del repositorio o el correo de contacto del perfil). Reclamación
  ante la AEPD si procede.

## Propiedad intelectual

El software es de código abierto (MIT, ver `LICENSE`). Los datos son de sus
titulares (AEMET, Copernicus/Open-Meteo, PATFOR/GVA, CNIG/IGN, Dirección General del
Catastro, MITECO, ICV/GVA) con las licencias y atribuciones que constan en el README
y en docs/02.
