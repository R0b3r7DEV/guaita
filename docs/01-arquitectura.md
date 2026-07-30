# 01 · Arquitectura

## Vista de componentes

```
                    ┌──────────────────────────────┐
  Fuentes externas  │  NASA FIRMS   AEMET OpenData │
                    │  EFFIS        CNIG/IGN       │
                    │  PATFOR GVA   Catastro INSPIRE│
                    └──────────────┬───────────────┘
                                   │
              ┌────────────────────┴────────────────────┐
              │                                         │
     ┌────────▼─────────┐                    ┌──────────▼─────────┐
     │  etl/  (Python)  │                    │ api/ingest (Java)  │
     │  GDAL / ogr2ogr  │                    │ @Scheduled         │
     │  Carga puntual   │                    │ Feeds diarios      │
     │  de geodatos     │                    │ FIRMS, AEMET       │
     │  pesados         │                    │                    │
     └────────┬─────────┘                    └──────────┬─────────┘
              │                                         │
              └──────────────┬──────────────────────────┘
                             │
                 ┌───────────▼────────────┐
                 │  PostgreSQL 16 +       │
                 │  PostGIS 3.4           │
                 │  (SRID de trabajo 25830)│
                 └───────────┬────────────┘
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
┌───────▼────────┐  ┌────────▼────────┐  ┌────────▼────────┐
│ api/risk       │  │ api/wui         │  │ MVT tiles       │
│ FWI + índice   │  │ Interfaz U-F    │  │ ST_AsMVT        │
│ compuesto      │  │ Buffers PostGIS │  │                 │
└───────┬────────┘  └────────┬────────┘  └────────┬────────┘
        └────────────────────┼────────────────────┘
                    ┌────────▼────────┐
                    │ api/web  REST   │
                    │ + api/alerts    │
                    └────────┬────────┘
                             │  HTTPS (Apache reverse proxy)
                    ┌────────▼────────┐
                    │ web/ React+Vite │
                    │ MapLibre GL     │
                    └─────────────────┘
```

## Decisiones (y por qué)

### ADR-01 — Geoprocesado en PostGIS, no en la aplicación

**Contexto.** Hace falta buffers de 30 m sobre decenas de miles de edificios,
intersecciones contra la capa forestal, agregaciones por término municipal.

**Decisión.** Todo en SQL con PostGIS. Java orquesta y persiste.

**Motivo.** GeoTools en Java es pesado, mal documentado y lento comparado con
GEOS. PostGIS con índices GiST resuelve la intersección de 50.000 edificios
contra la capa forestal en segundos. Además el geoprocesado queda versionado en
las migraciones Flyway como funciones SQL, revisables y testeables.

**Consecuencia.** La lógica geoespacial no es portable fuera de PostgreSQL. Se
asume: no se va a migrar de motor.

---

### ADR-02 — ETL de geodatos estáticos separado del backend

**Contexto.** Cargar el MDT, los límites municipales, la capa PATFOR y el
Catastro son operaciones puntuales, pesadas (GB), y que se repiten cada muchos
meses. Los feeds vivos (FIRMS, AEMET) son ligeros y diarios.

**Decisión.** Dos caminos distintos. `etl/` en Python con GDAL para lo estático,
ejecutado a mano o por `make seed`. Spring `@Scheduled` para lo vivo.

**Motivo.** Meter la carga de un shapefile de 2 GB dentro del ciclo de vida de
Spring Boot es innecesario y hace el arranque frágil. `ogr2ogr` lleva 20 años
haciendo exactamente eso.

**Consecuencia.** Hay dos runtimes. Aceptable: el contenedor de ETL solo se
levanta cuando se usa.

---

### ADR-03 — SRID 25830 para cálculo, 4326 en la frontera

**Contexto.** Calcular distancias en EPSG:4326 (grados) da resultados sin
sentido. Es el error geoespacial más común y más silencioso.

**Decisión.** Todas las columnas `geometry` se almacenan en **EPSG:25830**
(ETRS89 / UTM zona 30N), que es el sistema oficial peninsular. La API convierte a
4326 solo al serializar GeoJSON, con `ST_Transform`.

**Motivo.** 25830 es métrico. Un `ST_Buffer(geom, 30)` significa 30 metros y no
hay que pensar.

**Consecuencia.** Toda geometría entrante debe reproyectarse en la ingesta. Sin
excepciones. Añadir un `CHECK (ST_SRID(geom) = 25830)` en cada tabla.

---

### ADR-04 — Tiles vectoriales generados desde PostGIS

**Contexto.** El visor tiene que pintar 135 municipios coloreados por índice, más
focos FIRMS, más la capa de interfaz. GeoJSON completo son megabytes.

**Decisión.** Endpoint `/tiles/{layer}/{z}/{x}/{y}.mvt` implementado con
`ST_AsMVT` + `ST_AsMVTGeom`, con caché HTTP.

**Motivo.** Evita una dependencia más (`pg_tileserv`) y da control sobre qué
atributos viajan. `ST_AsMVT` es rápido y está en PostGIS desde la 2.4.

**Consecuencia.** Hay que implementar la conversión z/x/y → bbox. Es aritmética
conocida, va en una clase `TileMath` con tests.

---

### ADR-05 — Sin Kubernetes, sin microservicios

**Contexto.** Un desarrollador, un VPS, tiempo limitado por FCT y trabajo.

**Decisión.** Un monolito modular en Spring Boot + Docker Compose.

**Motivo.** El coste operativo de cualquier otra cosa se come el tiempo de
desarrollo. La modularidad se consigue con paquetes y límites claros, no con
procesos separados.

**Consecuencia.** Si algún día hace falta escalar la ingesta, el módulo `ingest`
está aislado y se puede extraer. No antes.

---

### ADR-06 — Teselas inmutables, datos dinámicos aparte

**Contexto.** El visor pinta 135 municipios y, desde la Fase 3, un coropleto por
nivel de peligro que cambia a diario. La tentación será meter el índice como
atributo del propio MVT para pintar el coropleto de una sola petición.

**Decisión.** Las teselas MVT llevan **solo geometría e identidad** (`ine_code`,
`nombre`, `comarca`). Son inmutables: `Cache-Control: public, max-age=31536000,
immutable`. Los datos dinámicos (índice, nivel, banderas) viajan por el endpoint
JSON `/municipios` (docs/06) y se unen **en cliente** por `ine_code` con
`setFeatureState` de MapLibre (la source se declara con `promoteId: 'ine_code'`).

**Motivo.** Si el índice viajara en la tesela, la caché —de un día o de un año—
serviría el peligro de AYER. En esta aplicación concreta ese es exactamente el
fallo que no podemos permitirnos (amenaza T7). Separar geometría de estado deja
la geometría cacheable para siempre y el estado —un JSON de 135 filas— trivial
de refrescar con `max-age` corto. Actualizar el índice no invalida ni regenera
ninguna tesela.

**Consecuencia.** El cliente hace dos peticiones (teselas + JSON) y las une por
`ine_code`. Es el patrón estándar de mapas temáticos y MapLibre lo soporta de
fábrica con `feature-state`. El encuadre inicial del visor se pide a
`/mapa/extent` (envolvente continental, no la administrativa: docs/06).

## Módulos del backend

| Módulo | Responsabilidad | Depende de |
|---|---|---|
| `ingest` | Traer datos externos, normalizar, persistir. Idempotente. | — |
| `risk` | Calcular FWI y el índice compuesto diario. | `ingest` |
| `wui` | Análisis de interfaz urbano-forestal y generación de informes. | — |
| `alerts` | Suscripciones y envío de avisos por umbral. | `risk` |
| `web` | Controladores REST, DTOs, serialización, tiles. | todos |

Regla: los módulos se comunican por interfaces de servicio, nunca por
repositorios ajenos. `wui` no toca las tablas de `risk`.

## Despliegue

VPS con Apache delante como reverse proxy (ya en uso para XPL0DAY). Compose
levanta `db`, `api`, `web` (nginx sirviendo el build estático de Vite).
Certificados por Let's Encrypt. Backup diario de PostgreSQL con `pg_dump`
comprimido y rotación a 7 días — el geodato estático se puede reconstruir con
`make seed`, pero los índices históricos calculados no.
