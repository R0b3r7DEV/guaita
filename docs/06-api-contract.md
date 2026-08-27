# 06 · Contrato de API

Base: `/api/v1`. JSON. Fechas ISO-8601. Geometrías GeoJSON en **EPSG:4326**
(conversión con `ST_Transform` en la frontera; internamente todo es 25830).

## Convenciones

- Errores con **RFC 7807** (`application/problem+json`).
- Paginación por cursor, no por offset.
- `ETag` + `Cache-Control` en todo lo cacheable. El índice diario cambia una vez
  al día: `max-age=3600, stale-while-revalidate=86400`.
- Sin autenticación para lectura pública. JWT para detalle IUF y suscripciones.

## Endpoints públicos

### `GET /municipios`
Lista de municipios con su índice más reciente. El objeto `banderas` tiene la
**misma forma** que en el detalle (`regla303030`, `vientoAlineado`), para que el
cliente no maneje dos representaciones.

```json
{
  "data": [
    { "ineCode": "12126", "nombre": "la Vall d'Uixó", "comarca": "Plana Baixa",
      "indice": 78.4, "nivel": 4, "fecha": "2026-07-26",
      "banderas": { "regla303030": true, "vientoAlineado": true } }
  ],
  "meta": { "fecha": "2026-07-26", "versionModelo": "v1.0" }
}
```

### `GET /municipios/{ineCode}`
Detalle: desglose de los tres componentes, códigos FWI del día, serie de los
últimos 30 días, y contadores agregados de IUF.

```json
{
  "ineCode": "12126",
  "nombre": "la Vall d'Uixó",
  "fecha": "2026-07-26",
  "indice": 78.4,
  "nivel": 4,
  "componentes": { "meteo": 88.1, "estructural": 71.2, "vulnerabilidad": 66.0 },
  "fwi": { "ffmc": 92.3, "dmc": 84.1, "dc": 512.7, "isi": 14.2, "bui": 96.5, "fwi": 41.8 },
  "banderas": { "regla303030": true, "vientoAlineado": true },
  "calidadDato": { "deltaAltitudM": 132.0, "elevacionCeldaM": 912.0 },
  "iufResumen": { "total": 1240, "critico": 18, "incumple": 96, "ajustado": 41 },
  "versionModelo": "v1.0"
}
```

`calidadDato` no es decorativo: la fuente es un reanálisis en rejilla (ADR-07) y
`deltaAltitudM` es cuánto tuvo que downscalear el modelo desde la cota de su celda
(`elevacionCeldaM`) hasta la altitud media del municipio. Un `deltaAltitudM`
grande (Vistabella, Villahermosa) merece menos confianza que uno ≈ 0 (Nules) y el
usuario debe verlo. Se sirve desde `meteo_municipio`, no desde `fwi_municipio`
(ver doc 03).

### `GET /municipios/{ineCode}/serie?desde=&hasta=`
Serie histórica diaria del índice y del FWI. Máximo 5 años por petición.

### `GET /focos?desde=&hasta=&bbox=&confianza=`
Focos térmicos FIRMS, agrupados por `cluster_id`. GeoJSON `FeatureCollection`.
Por defecto últimas 24 h y `confianza != low`.

Respuesta con aviso obligatorio en `meta`:
```json
{ "meta": { "aviso": "Detección satelital con latencia aproximada de 3 h. No es un sistema de alerta temprana. Ante un incendio, llame al 112." } }
```

### `GET /incendios-historicos`
Eventos conocidos con perímetro, para la capa de contexto y el backtest.

### `GET /tiles/{layer}/{z}/{x}/{y}.mvt`
Tiles vectoriales (`Content-Type: application/vnd.mapbox-vector-tile`). `layer` ∈
`municipios` | `forestal` | `iuf` | `focos`. `iuf` devuelve solo agregado por
municipio en z < 12; detalle por edificación únicamente en z >= 14 **y con JWT
válido**. `z` máximo 16 (T4, docs/07); por encima, 400. Una tesela sin geometría
devuelve **204**, nunca 404 ni un MVT vacío con pinta de error.

**Política de caché (ADR-06).** Las teselas llevan **solo geometría e identidad**
(`ine_code`, `nombre`, `comarca`) y son inmutables: `Cache-Control: public,
max-age=31536000, immutable`, con `ETag` de contenido para revalidar tras un
re-seed. El estado dinámico (índice, nivel, banderas) **no viaja en la tesela**:
se pide a `/municipios` (JSON, `max-age` corto) y se une en cliente por
`ine_code`. Así actualizar el índice no invalida ninguna tesela.

### `GET /mapa/extent`
Envolvente **continental** de la provincia en EPSG:4326 (desde
`mv_provincia_continental`, no la administrativa: esta incluye las Columbretes y
encuadraría el visor sobre mar abierto). Para el `fitBounds` inicial del visor.

```json
{ "bbox": [-0.77, 39.71, 0.30, 40.79] }
```

`Cache-Control: public, max-age=86400` (el encuadre solo cambia tras un re-seed
de límites municipales).

### `GET /metodologia`
Devuelve la versión del modelo, los pesos vigentes y el enlace a la
documentación. La transparencia metodológica es parte del producto.

### `GET /wui/agregado/{ineCode}`  — PÚBLICO (T2)
Recuentos de interfaz urbano-forestal por clase (`critico`, `incumple`, `cumple`),
más `advertenciaMargen` **etiquetada como cautela técnica, no incumplimiento**, y
`franja50Pendiente`. Interés general, no identifica a nadie. **200**; **404** si el
municipio no existe.

## Endpoints autenticados

### `POST /auth/login`  ·  `POST /auth/refresh`  ·  `POST /auth/logout`
Login → access token (15 min, en el cuerpo) + refresh (cookie `HttpOnly; Secure;
SameSite=Strict`, rotación). **401** genérico si las credenciales fallan (sin
enumerar). Refresh rota el token; reutilización → revoca la familia → **401**.
Logout revoca la familia. Rate limit 5/h por IP en login (docs/07).

### `GET /wui/municipio/{ineCode}`  — JWT (T2)
Detalle por edificación: **solo referencia catastral y coordenada** (lat/lon),
clase, `franjaM` (30/50 según pendiente) y `advertenciaMargen`. **Nunca titularidad
ni direcciones.** Un técnico solo ve SU término. **200** propio; **401** sin JWT;
**403** JWT de otro término; **429** si supera el rate limit.

### `GET /tiles/wui/{z}/{x}/{y}.mvt`  — JWT (T2)
Geometría de edificaciones solo a **z ≥ 14** y autenticado, filtrada al término del
usuario; por debajo de z 14, **204** (para la escala está el agregado, público).
Cada feature lleva solo `ref_catastral` y `clase`. **401** sin JWT.

### `POST /suscripciones`

### `POST /suscripciones`
```json
{ "email": "…", "ineCode": "12126", "umbral": 4 }
```
Doble opt-in: crea la suscripción como no verificada y envía correo con token.
Responde **202** siempre, exista el email o no (no filtrar existencia).

### `GET /suscripciones/verificar?token=`
### `DELETE /suscripciones/{id}?token=`
Baja en un clic, sin login. Token de un solo uso con caducidad.

### `GET /wui/informe/{ineCode}.pdf`  — JWT (T2)
Informe municipal IUF en PDF: avisos (descargo + limitación MDT25 + error catastral)
en la primera página, cita literal del Anexo XI y remisión del art. 145, resumen por
clase con la cautela técnica separada, mapa del término, y tabla por distancia
(referencia catastral + coordenada, nada más). Requiere JWT, **solo del término
autorizado** (otro término → **403**), rate limit 10/hora/usuario.

## Códigos de error

| Código | Situación |
|---|---|
| 400 | Parámetros inválidos (bbox mal formado, rango > 5 años) |
| 401 | Falta JWT en endpoint protegido |
| 403 | JWT válido sin permiso sobre ese municipio |
| 404 | `ineCode` inexistente |
| 422 | Municipio sin índice para la fecha (p. ej. antes del arranque de la serie) |
| 429 | Rate limit |
| 503 | Fuente externa caída durante la ingesta; se sirve el último dato válido |

En 503 **nunca** devolver datos vacíos silenciosamente. Servir el último cálculo
con `meta.obsoleto: true` y su fecha. Un índice de ayer etiquetado como tal es
útil; un cero sin explicación es peligroso.

## OpenAPI

`springdoc-openapi` expone `/api/v1/openapi.json`. El contrato de este documento
manda; si divergen, es un bug del código.
