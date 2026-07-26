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
  "calidadDato": { "interpolado": false, "estaciones": 1 },
  "iufResumen": { "total": 1240, "critico": 18, "incumple": 96, "ajustado": 41 },
  "versionModelo": "v1.0"
}
```

`calidadDato` no es decorativo: un índice interpolado desde tres estaciones
lejanas merece menos confianza y el usuario debe verlo. Se sirve desde
`meteo_municipio` (`interpolado`, `n_estaciones`), no desde `fwi_municipio`
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
Tiles vectoriales. `layer` ∈ `municipios` | `forestal` | `iuf` | `focos`.
`iuf` devuelve solo agregado por municipio en z < 12; detalle por edificación
únicamente en z >= 14 **y con JWT válido**.

### `GET /metodologia`
Devuelve la versión del modelo, los pesos vigentes y el enlace a la
documentación. La transparencia metodológica es parte del producto.

## Endpoints autenticados

### `POST /suscripciones`
```json
{ "email": "…", "ineCode": "12126", "umbral": 4 }
```
Doble opt-in: crea la suscripción como no verificada y envía correo con token.
Responde **202** siempre, exista el email o no (no filtrar existencia).

### `GET /suscripciones/verificar?token=`
### `DELETE /suscripciones/{id}?token=`
Baja en un clic, sin login. Token de un solo uso con caducidad.

### `GET /municipios/{ineCode}/iuf/informe.pdf`
Informe completo del término. Requiere JWT. Rate limit estricto.

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
