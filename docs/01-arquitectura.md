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
                             │  HTTPS (nginx reverse proxy, contenedor frontend)
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

---

### ADR-07 — Fuente meteo única (reanálisis) para histórico Y operación

**Contexto.** El componente meteorológico del índice se normaliza a **percentiles
sobre la serie histórica local** de cada municipio (docs/04 §1). Si se calibra la
distribución con una fuente (reanálisis Open-Meteo) y se evalúa la operación con
otra (observación AEMET + IDW), sus sesgos sistemáticos distintos corren los
percentiles: un municipio de montaña con +1,5 °C sistemático puntuaría alto de
forma **permanente**, con números plausibles y nada que falle. El backtest de la
Fase 4 quedaría calibrado sobre una distribución que no es la de producción.

**Decisión.** **Una sola fuente para todo: Open-Meteo, modelo ERA5-Seamless**
(temperatura y HR de ERA5-Land ~11 km, viento y precipitación de ERA5 ~25 km).
Alimenta tanto el backfill histórico (1940→) como la operación diaria. AEMET
**deja de ser entrada** y pasa a **contraste externo** en la validación (Fase 4),
junto a PREVIFOC (docs/02 §7, docs/09).

**Motivo.** (1) Es la única opción que elimina de raíz el desajuste
calibración↔evaluación, en vez de parchearlo con una corrección de sesgo por
municipio que además puede no ser estacionaria. (2) ERA5 es un reanálisis
**homogéneo**, justo lo que necesita una climatología de percentiles estable. (3)
Historia profunda para el backtest de 20 años. (4) Como contraste **independiente**
—distinta física y observación real—, AEMET vale más que como entrada sesgada.
ERA5-Land solo NO sirve: no trae viento a 10 m (verificado en la API).

**Consecuencia.** El archivo ERA5 tiene **~5 días de latencia** (no hay reanálisis
de "hoy"; la API de previsión de Open-Meteo usa modelos NWP distintos, así que
mezclarla reabriría el problema). El índice operativo va, por tanto, **~5 días
atrasado**: el job diario calcula el último día disponible y lo **etiqueta con su
fecha**. Es aceptable y honesto porque GUAITA **no es un sistema de emergencia**
(amenaza T7); para eso está el 112 / PREVIFOC. Limitación de resolución: el viento
queda a ~25 km (ERA5-Land no lo downscalea); la temperatura, donde más pesa el
desnivel de 1.800 m, sí baja a ~11 km, y la corrección altitudinal (docs/04) actúa
sobre ella. Mediodía fijado en 12:00 UTC (criterio EFFIS, docs/04).

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

**Producción real (guaita.xpl0day.com).** El VPS comparte máquina con XPL0DAY. El
montaje que quedó no es el que se preveía (Apache); documentado tal cual es:

- **Puerta pública = un contenedor nginx propio de XPL0DAY** (`xpl0day-frontend`,
  daemon Docker de *root*) que ata `:80`/`:443`. No es Apache ni un proxy con
  auto-TLS. GUAITA se añade como un `server_name guaita.xpl0day.com` en su
  `nginx/default.conf` (fichero versionado en `ops/nginx-guaita.conf`): proxy `/`
  → visor y `/api/` → API.
- **GUAITA corre en Docker ROOTLESS** (usuario `guaita`), un daemon **distinto**
  del de root. Un contenedor del daemon root **no alcanza** los puertos publicados
  por el rootless a través del bridge (probado: `builtin` y `slirp4netns` fallan
  con timeout aunque INPUT los permita). Solución: `db`/`api`/`web` publican en
  **`127.0.0.1`** (loopback, cerrados a Internet) y el frontend corre en
  **`network_mode: host`** para alcanzarlos ahí. El bind se controla por `.env`
  (`DB_BIND`/`API_BIND`/`WEB_BIND`).
- **TLS + renovación automática (DNS-01 con IONOS)**: cert dedicado de Let's
  Encrypt para `guaita.xpl0day.com` en `/etc/letsencrypt/live/guaita.xpl0day.com/`
  (el contenedor lo ve por el montaje `/etc/letsencrypt:ro`). El challenge es
  **DNS-01** vía el plugin `certbot-dns-ionos`, que crea el TXT `_acme-challenge`
  por la **API de DNS de IONOS** — no necesita el `:80` (que tiene el contenedor).
  - El plugin exige `certbot ≥ 3`, incompatible con el `certbot 2.9` de apt (choca
    con el `josepy` de Debian). Solución: **venv aislado en `/opt/certbot-ionos/`**
    (`certbot` + `certbot-dns-ionos`), sin tocar el certbot del sistema.
  - Credenciales en `/etc/letsencrypt/ionos.ini` (chmod 600; `dns_ionos_prefix` /
    `dns_ionos_secret`, endpoint `api.hosting.ionos.com`).
  - **Auto-renovación**: override de systemd
    `/etc/systemd/system/certbot.service.d/venv.conf` apunta el `certbot.timer` al
    binario del venv; cada cert lleva `--deploy-hook 'docker exec
    xpl0day-frontend-1 nginx -s reload'` para recargar el frontend tras renovar.
  - **Comprobar que sigue vivo**: `sudo /opt/certbot-ionos/bin/certbot renew
    --dry-run` (debe decir "all simulations succeeded") y `... certbot certificates`
    (fechas futuras). Verificado para guaita **y** xpl0day (este último estaba
    caducado desde julio: se arregló de paso por la misma vía).
  - Cabeceras de seguridad en el server block (CSP con las excepciones de MapLibre:
    `worker-src blob:`, etc.), `limit_req` para las teselas (T4), CORS innecesario
    (visor y API mismo origen).
- **Cómo revertir** (si el frontend rompe XPL0DAY): en `~/xpl0day`,
  `cp docker-compose.yml.bak docker-compose.yml && cp nginx/default.conf.bak
  nginx/default.conf && sudo docker compose up -d --build`. Backup completo de la
  config web en `/root/webconf-backup-*.tar.gz`. **Salvaguarda de despliegue**:
  `sudo docker compose run --rm --entrypoint nginx frontend -t` valida la config
  ANTES de recrear; si falla, XPL0DAY sigue intacto.
- **Durabilidad**: `loginctl enable-linger guaita` (el rootless arranca en boot) +
  `restart: unless-stopped` en los tres contenedores ⇒ GUAITA revive tras reinicio.

Backup de PostgreSQL con `pg_dump -Fc` — el geodato estático se reconstruye con
`make seed`, pero el backfill de FWI (días de descarga) y los índices calculados
no; hay que preservarlos.

**Reglas iptables retiradas:** durante el diagnóstico del despliegue se probaron
reglas `INPUT -i docker0 --dport 5173/8080 ACCEPT` para intentar que el frontend
(daemon root) alcanzara los contenedores rootless por el bridge. NO funcionó (el
forwarder rootless no acepta tráfico del bridge, ni con `builtin` ni
`slirp4netns`), y se resolvió con loopback + `network_mode: host`. Las reglas se
**retiraron** por no dejar ruido inexplicable; no eran necesarias.

**Deuda ajena a GUAITA, ya resuelta de paso:** el cert de `xpl0day.com` estaba
caducado (desde julio) porque su certbot chocaba con el `:80` del contenedor. Al
automatizar la renovación por DNS-01 (arriba) se re-emitió y quedó auto-renovando.
El subdominio `practicas` se eliminó y su cert se borró de certbot.

## Limitación operativa — estado y CI

**Los runners de GitHub Actions no persisten estado entre ejecuciones.** Cada
`workflow_dispatch` es una VM nueva: el volumen `db_data` nace vacío, se puebla
durante el job y se destruye con la VM al terminar. Solo sobreviven los
artefactos y lo commiteado. Consecuencia para GUAITA: **cualquier proceso con
estado recursivo o incremental —el FWI encadena cada día sobre el anterior— NO
puede trocearse en varios dispatches**, porque la reanudabilidad lee el estado
previo de la BD y la BD no sobrevive. El primer informe de backfill (16
municipios) funcionó porque hizo fetch → cálculo → informe en un solo job; su BD
desapareció con la VM.

Por eso el **backfill histórico completo (135 municipios × ~21 años) corre en el
VPS contra una BD con volumen persistente**, no en Actions: IP propia con
presupuesto diario fresco frente a la fuente externa, volumen que sobrevive a
`make down`, sin límite de 90 min por job, y encima es el entorno de despliegue
real. Actions queda para lo *sin estado*: build, tests, lint, smoke y el seed de
geodato estático (idempotente y reconstruible). Regla general: **estado recursivo
⇒ BD persistente ⇒ VPS, no CI.**

## Despliegue en Docker rootless — aislamiento y permisos

En el VPS, GUAITA corre en un **Docker rootless** de un usuario dedicado (`guaita`,
sin `sudo`), con su **propio daemon** separado del de sistema donde vive XPL0DAY:
GUAITA no puede ver ni tocar los contenedores/volúmenes de producción — aislamiento
por diseño, no por permisos. Dos lecciones que costaron tiempo:

- **Cuenta `--disabled-password` + `UsePAM no` en sshd** bloquea el login por CLAVE,
  no solo por contraseña (sshd rechaza cuentas "locked"). Se desbloquea dándole un
  hash aleatorio (`usermod -p`), que no habilita login por contraseña pero deja de
  estar "locked".
- **Escritura de ficheros en un bind-mount:** el contenedor `api` corre como usuario
  no-root (`USER guaita` en el Dockerfile, buena práctica). Bajo rootless ese uid
  mapea a un *subuid* que **no es dueño** del directorio del host montado, así que el
  finalize del backfill fallaba al escribir el informe (`AccessDeniedException`). Se
  resuelve corriendo **ese contenedor** con `docker run --user 0:0`: bajo rootless el
  uid 0 mapea al **usuario dueño del host** (no a root real), y escribe con permisos
  normales. Evita el `chmod 777` del bind-mount.
