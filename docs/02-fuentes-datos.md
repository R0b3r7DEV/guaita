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

**Alternativa/complemento.** Open-Meteo no requiere clave y da reanálisis
histórico horario, que para el backtest es más cómodo que pelearse con el
archivo de AEMET. Considerar usar Open-Meteo para histórico y AEMET para
operación diaria.

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
