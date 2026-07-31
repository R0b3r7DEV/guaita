# GUAITA

**Sistema de inteligencia de riesgo de incendio forestal — provincia de Castellón**

> `guaita` (val.): atalaya, torre de vigía.

Índice de peligro diario por municipio calculado con el sistema FWI canadiense
sobre datos abiertos, análisis de interfaz urbano-forestal a partir de
cartografía catastral y PATFOR, y validación del índice contra el histórico real
de incendios de la provincia.

---

> ⚠️ **GUAITA no es un sistema de emergencias.** No sustituye al 112 ni al
> boletín oficial PREVIFOC de la Generalitat Valenciana. Ante un incendio,
> llame al **112**.

---

## Por qué

Castellón ha sufrido cuatro eventos graves en cuatro años —Bejís (2022, ~19.000
ha), Les Useres (2022), Villanueva de Viver (2023, 4.700 ha) y la Serra d'Espadà
(2026)— mientras los planes de prevención de demarcación sobre los que se
planifica fueron redactados entre 2007 y 2013 y actualizados en 2013–2014.

## Visor

Los 135 términos de la provincia en un visor MapLibre servido por teselas
vectoriales propias (`ST_AsMVT` desde PostGIS, sin `pg_tileserv` ni token de
Mapbox). Clic en un término muestra su nombre y comarca; el coropleto por nivel de
peligro llega en la Fase 3 sin rehacer la capa (las teselas ya declaran
`promoteId: 'ine_code'`, ADR-06).

<!-- Captura pendiente: docs/img/visor.png. Se genera levantando el stack con datos
     (make up && make seed) y fotografiando http://localhost:5173. No se incluye una
     imagen fabricada. -->
> _Captura del visor: pendiente de `docs/img/visor.png` — se obtiene con el stack
> levantado y sembrado (`make up && make seed`), sobre `http://localhost:5173`._

## Stack

Java 21 · Spring Boot 4.1 · PostgreSQL 16 + PostGIS 3.4 · React 19 + Vite ·
MapLibre GL · Docker Compose

## Arranque

```bash
cp .env.example .env     # rellenar FIRMS_MAP_KEY y AEMET_API_KEY
make up
make seed                # geodatos base (tarda; descarga varios GB)
make ingest              # primera pasada de feeds
```

Visor en `http://localhost:5173`, API en `http://localhost:8080/api/v1`.

## Despliegue en VPS

El VPS puede compartir máquina con otra plataforma en producción (XPL0DAY). El
override [`docker-compose.vps.yml`](docker-compose.vps.yml) **no toca el compose
base** y añade el blindaje para que GUAITA —y en particular el backfill, que
escribe durante horas— no monopolice ni tumbe la máquina:

- **Puertos remapeables** por `.env` (`DB_PORT_HOST`, `API_PORT_HOST`,
  `WEB_PORT_HOST`) para esquivar colisiones sin editar ficheros.
- **Límites de recursos** (`mem_limit`/`cpus`) en `db` (1 GB/1 CPU — PostGIS
  sobre una provincia cabe de sobra) y `api` (1 GB/1 CPU — pico al parsear el
  JSON horario de un año × 135 municipios, ~43 MB). Defaults conservadores;
  ajústalos en `.env` tras mirar `free -h` y `docker stats`.
- **Reinicio** `unless-stopped` en los servicios de larga duración.
- **Rotación de logs** (`max-size`/`max-file`) en todos: sin ella el json-file de
  Docker crece sin límite y llena el disco (caída clásica en VPS pequeños).

```bash
# Levantar con el override
docker compose -f docker-compose.yml -f docker-compose.vps.yml up -d --wait
make seed        # geodatos base
```

### Backfill histórico desatendido

El backfill NO corre en GitHub Actions (su BD es efímera: ver
[docs/01](docs/01-arquitectura.md), "Limitación operativa"). Corre en el VPS,
contra el volumen persistente, con una **guardia de disco** que aborta limpio
(BD consistente, reanudable) si el libre baja de `DISK_MIN_FREE_GB` (5 GB por
defecto), y que se niega a arrancar si el tramo no cabe con margen.

Los **tramos intermedios** solo ingieren meteo (`finalize` ausente); el **tramo
final** (`"guaita.backfill.finalize":true`) calcula el FWI de los 135 sobre la
serie completa, verifica que la meteo está completa (aborta diciendo qué falta),
corre las aserciones de completitud y regenera el informe.

```bash
# Tramo intermedio (ej. 2005-2008), sobreviviendo a la desconexión SSH:
nohup docker compose -f docker-compose.yml -f docker-compose.vps.yml \
  run --rm --name guaita-backfill \
  -e SPRING_APPLICATION_JSON='{"guaita.backfill.run":true,"guaita.backfill.from":2005,"guaita.backfill.to":2008,"guaita.backfill.report-path":"/out/fwi-backfill.md"}' \
  -v "$(pwd)/etl/reports:/out" \
  api > "etl/reports/backfill-$(date +%Y%m%d-%H%M).log" 2>&1 &

# Tramo FINAL (añade "finalize":true): calcula FWI + aserciones + informe.
#   ...,"guaita.backfill.to":2026,"guaita.backfill.finalize":true,...
```

Progreso, dos vías:

```bash
# a) Desde el log (último año procesado + disco libre):
grep -hE "meteo [0-9]{4} ->|GUARDIA DE DISCO" etl/reports/backfill-*.log | tail -n 3; \
  df -h "${DISK_GUARD_HOST_PATH:-/}" | tail -1

# b) Desde la BD, sin leer el log (municipios cubiertos y rango ingerido):
docker compose -f docker-compose.yml -f docker-compose.vps.yml exec db \
  psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c \
  "select count(distinct ine_code) municipios, min(fecha), max(fecha), count(*) filas \
   from meteo_municipio;"
```

Reanudar tras un aborto/parada = relanzar el mismo tramo: es idempotente y
retoma el estado desde la BD (que ahora persiste en el volumen del VPS).

## Documentación

| Doc | Contenido |
|---|---|
| [00](docs/00-vision-alcance.md) | Visión, usuarios, alcance y no-alcance |
| [01](docs/01-arquitectura.md) | Arquitectura y decisiones (ADR) |
| [02](docs/02-fuentes-datos.md) | Fuentes, endpoints, licencias, trampas |
| [03](docs/03-modelo-datos.md) | Esquema PostGIS |
| [04](docs/04-indice-peligro.md) | **Especificación del índice. Cerrada.** |
| [05](docs/05-interfaz-urbano-forestal.md) | Módulo IUF |
| [06](docs/06-api-contract.md) | Contrato REST |
| [07](docs/07-seguridad.md) | Modelo de amenazas y RGPD |
| [08](docs/08-roadmap.md) | Plan por fases |
| [09](docs/09-validacion.md) | Metodología de backtesting |

## Datos y atribución

NASA FIRMS · AEMET OpenData · Copernicus EFFIS · PATFOR (Generalitat Valenciana)
· CNIG/IGN · Dirección General del Catastro (INSPIRE).

## Licencia

MIT. Ver [LICENSE](LICENSE).
