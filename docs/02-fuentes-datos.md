# 02 · Fuentes de datos

> **Verificar endpoints antes de implementar.** Las URLs y condiciones de uso
> cambian. Cada fuente lleva abajo cómo comprobarla. No dar por buena ninguna
> ruta de este documento sin un `curl` previo.

## Resumen

| Fuente | Qué aporta | Cadencia | Clave | Tipo |
|---|---|---|---|---|
| NASA FIRMS | Focos térmicos activos e histórico | Diaria (NRT ~3h) | Sí, gratis | Feed vivo |
| AEMET OpenData | Temp, HR, viento, precipitación | Diaria | Sí, gratis | Feed vivo |
| EFFIS / Copernicus | Perímetros de área quemada, FWI europeo | Eventual | No | Feed vivo |
| PATFOR (GVA) | Terreno forestal, modelos de combustible | Anual o menos | No | Estático |
| CNIG / IGN | MDT, límites municipales, PNOA | Anual o menos | No | Estático |
| Catastro INSPIRE | Edificaciones, parcelas | Semestral | No | Estático |

---

## 1. NASA FIRMS — focos térmicos

**Para qué.** Detección de incendios activos y, sobre todo, el **archivo
histórico** que alimenta el backtest. Sensores VIIRS (375 m, tres satélites:
S-NPP, NOAA-20, NOAA-21) y MODIS (1 km, legado).

**Acceso.** MAP_KEY gratuito solicitándolo en el portal de FIRMS. API de área:

```
https://firms.modaps.eosdis.nasa.gov/api/area/csv/{MAP_KEY}/{SOURCE}/{AREA}/{DAY_RANGE}/{DATE}
```

- `SOURCE`: `VIIRS_SNPP_NRT`, `VIIRS_NOAA20_NRT`, `VIIRS_NOAA21_NRT`, `MODIS_NRT`.
  Para histórico, los sufijos `_SP` (standard processing).
- `AREA`: bbox `oeste,sur,este,norte`. **Castellón: `-1.05,39.68,0.62,40.82`**
  (holgado, se recorta luego contra el límite provincial en PostGIS).
- `DAY_RANGE`: 1–10.

**Trampas.**
- Sin resultados devuelve **solo la cabecera CSV**, HTTP 200. Cero filas es un
  caso válido, no un error. Ya mordió a otros.
- Hay cuota de peticiones por MAP_KEY. Cachear, no reintentar en bucle.
- Un foco **no es un incendio**. Son quemas agrícolas, industria, reflejos.
  Filtrar por `confidence` (descartar `low`) y por `type` (0 = vegetación).
- La latencia NRT es de unas 3 horas. Inútil para ataque inicial, perfecto para
  histórico y seguimiento. Dejar esto claro en la UI.

**Clustering.** Los focos sueltos no sirven. Agrupar con `ST_ClusterDBSCAN`
(eps ≈ 1000 m, minpoints = 2) sobre ventanas de 24 h para reconstruir eventos.

**Licencia.** Datos públicos de NASA, atribución requerida.

---

## 2. AEMET OpenData — meteorología

**Para qué.** Las cuatro variables que come el FWI: temperatura, humedad
relativa, velocidad de viento y precipitación acumulada 24 h, tomadas al
mediodía solar.

**Acceso.** API key gratuita por formulario. Patrón en dos pasos: la primera
llamada devuelve un JSON con un campo `datos` que contiene la URL real donde
está el payload. **Hay que hacer dos peticiones.** Es la peculiaridad de esta API
y sorprende a todo el mundo la primera vez.

Endpoints de interés:
- Observación convencional de todas las estaciones (`/observacion/convencional/todas`).
- Predicción diaria por municipio (`/prediccion/especifica/municipio/diaria/{codigo}`).
- Avisos de fenómenos adversos por CCAA.

**Trampas.**
- Rate limit estricto. Una pasada diaria, con backoff.
- No todos los municipios tienen estación. Hace falta **interpolar**: ver
  `docs/04-indice-peligro.md`, sección "asignación meteorológica".
- Los códigos de municipio son INE, no catastrales.

**Rol reasignado (ADR-07).** Tras analizar el problema de discontinuidad de
fuentes, **AEMET NO es la entrada del FWI**: la entrada es Open-Meteo (una sola
fuente para histórico y operación, ver abajo). AEMET queda como **contraste
externo** en la validación (Fase 4), junto a PREVIFOC (§7): comparar el índice de
GUAITA contra observación real es más valioso —e independiente— que usarla como
entrada con un sesgo sistemático distinto al del reanálisis.

## 2-bis. Open-Meteo — FUENTE METEO PRIMARIA (reanálisis)

**Para qué.** Las cuatro variables del FWI (temperatura, HR, viento, precipitación
24 h) a las **12:00 UTC** (criterio EFFIS, docs/04), para **histórico Y operación**
(ADR-07). Sin clave.

**Modelo: ERA5-Seamless.** Temperatura y HR de **ERA5-Land** (~11 km, donde más
pesa el desnivel de 1.800 m); viento y precipitación de **ERA5** (~25 km, porque
ERA5-Land no trae viento a 10 m). Cobertura 1940→. Endpoint `/v1/archive`.

**Trampas verificadas.**
- **Latencia ~5 días**: el archivo de reanálisis no tiene "hoy". El índice va ~5
  días atrasado, etiquetado con su fecha (aceptable: GUAITA no es emergencia, T7).
  La API de *forecast* de Open-Meteo usa modelos NWP distintos: **no mezclar** con
  el archivo o se reabre la discontinuidad (ADR-07).
- **Zona horaria**: la API horaria devuelve **UTC** por defecto (`timezone=GMT`).
  Se indexa la hora 12 UTC sin pasar `timezone`. La conversión, en un solo sitio.
- **Rate limit**: respetuoso, backoff exponencial, `User-Agent` identificable.
  135 municipios/día es trivial; el backfill (un rango largo por punto en una
  petición) también cabe de sobra.
- Alternativas descartadas: **ERA5-Land solo** (sin viento); **CERRA** (5 km, gran
  resolución, pero acaba en jun-2021 → solo histórico → reabriría la
  discontinuidad); **ECMWF IFS** (9 km, tiempo real, pero desde 2017 y modelo
  operacional no homogéneo para percentiles).

---

## 3. EFFIS / Copernicus

**Para qué.** Perímetros oficiales de área quemada (validación del backtest,
mucho mejor que reconstruirlos con FIRMS) y el European Forest Fire Danger
Forecast, útil como contraste externo del índice propio.

**Acceso.** EFFIS publica descargas de área quemada y servicios OGC. Copernicus
EMS Rapid Mapping activa cartografía específica en incendios grandes — el de
Espadà de julio 2026 debería tener activación.

**Uso.** Descarga puntual vía `etl/`. No es un feed diario.

---

## 4. PATFOR — Generalitat Valenciana

**Para qué.** La capa base sin la cual el proyecto no existe: **terreno forestal**
y modelos de combustible.

**Acceso.** Visor Cartográfico de la GVA, ruta
`Forestal / Instrumentos de Planificación / PATFOR / PATFOR temática / Incendios
forestales`. Descarga en formato shape.

**Trampas.**
- Los shapefiles no siempre declaran SRID. Forzar con `-a_srs EPSG:25830` si
  `-t_srs` falla.
- Nombres de campo truncados a 10 caracteres (limitación del formato DBF).
  Documentar el mapeo en `etl/mappings/patfor.yml`.
- La clasificación de combustible del PATFOR no es Scott & Burgan. Comprobar qué
  esquema usa (probablemente una adaptación del sistema Prometheus de 7 modelos,
  habitual en España) y mapearlo explícitamente. **No asumir.**

**Realidad verificada (Fase 1).**
- La capa de terreno forestal se toma del **WFS del ICV como GeoPackage**
  (`typename=SF.Forestal`, UTF-8, **SRID 25830 nativo**). Con esta fuente, las
  trampas de shapefile de arriba (SRID sin declarar, DBF ISO-8859-1, campos
  truncados a 10 caracteres) **no aplican**.
- `SF.Forestal` es **solo extensión** (atributos `forestal`/`compatible`/`prov`);
  **no contiene modelos de combustible**. El modelo de combustible es una capa
  aparte del mismo WFS: `ms:Regulacion.Incendios.Combustible` = «Modelo de
  combustible (clas. **Rothermel**, 13 modelos)» — **no Prometheus**. Pendiente
  para Fase 3 (ver docs/04 §2.2, RIESGO ABIERTO).
- **Recorte y limitación fronteriza.** El terreno forestal se recorta contra la
  geometría de trabajo continental **+ buffer de 5 km** (el fuego cruza límites:
  Bejís 2022 pasó de Castellón a Valencia). Pero el **PATFOR solo cubre la
  Comunitat Valenciana**, así que el buffer capta la franja **valenciana**
  fronteriza (Alt Palància, els Ports hacia Valencia) pero **no la aragonesa**
  (Teruel). Limitación conocida; la capa equivalente de Aragón queda fuera de
  alcance por ahora.

---

## 5. CNIG / IGN

**Para qué.**
- **MDT25 o MDT05** → pendiente y orientación. La pendiente entra en el índice;
  la orientación importa porque las solanas secan antes.
- **Límites municipales** (líneas límite jurisdiccionales) → unidad de análisis.
- **PNOA ortofoto** → capa base del visor.

**Acceso.** Centro de Descargas del CNIG, descarga libre con atribución.

**Cálculo.** Pendiente con `ST_Slope` de PostGIS Raster, o precalculada con
`gdaldem slope` en el ETL, que es más rápido. Preferir `gdaldem`.

---

## 6. Catastro — INSPIRE

**Para qué.** Geometrías de edificación para el módulo de interfaz
urbano-forestal.

**Acceso.** Servicios INSPIRE del Catastro, descarga de edificios y parcelas por
municipio en GML, gratuita y sin clave.

**Trampas / privacidad.**
- Se usa **únicamente** la geometría y la referencia catastral. **Nunca**
  titulares, nunca datos personales. Está en el alcance excluido del proyecto y
  en el modelo de amenazas.
- Volumen alto: 135 municipios en GML son muchos GB. Cargar por lotes.
- Filtrar por uso: interesan edificios residenciales y agrarios, no
  infraestructura lineal.

---

## 7. PREVIFOC (GVA) — nivel oficial de preemergencia

**Para qué.** AEMET emite diariamente un boletín con los niveles de preemergencia
para las 7 zonas en que se divide la Comunitat (1 baja-media, 2 alta, 3 extrema).
Es el contraste externo perfecto: si el índice de GUAITA no correlaciona con
PREVIFOC, algo está mal en el índice.

**Acceso.** ⚠️ **Verificar antes de tocar nada.** Si hay API o descarga
estructurada, usarla. Si solo hay web, revisar `robots.txt` y las condiciones de
uso antes de plantear scraping, y en ese caso ir con `User-Agent` identificable,
una petición al día y caché. Si las condiciones no lo permiten, **se descarta la
fuente**: el proyecto no depende de ella.

---

## Tabla de procedencia

Toda tabla de hechos incluye:

```sql
source        text        not null,  -- 'FIRMS_VIIRS_SNPP', 'AEMET_OBS', …
source_url    text,
fetched_at    timestamptz not null default now(),
```

Sin esto no entra el dato. Es la diferencia entre un proyecto serio y un scraper.
