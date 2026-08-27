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

## Demo pública

**https://guaita.xpl0day.com**

Visor coropleto de los 135 términos de Castellón por **nivel de peligro** (1 Bajo
→ 5 Extremo). Al clicar un municipio, el panel separa **Peligro** («cuán peligroso
es hoy»: meteo + estructural) de **Exposición** («qué hay en juego si arde»: la
interfaz urbano-forestal real), más los códigos FWI del día, la serie de 30 días y
las banderas. La fecha del dato y el aviso de "no es un sistema de emergencias" están
siempre visibles.

**Índice v1.1 (docs/09):** `comp_meteo_abs · modulador(comp_estructural)`. El
compuesto original no batía al FWI crudo, así que la **meteo absoluta** es la base y
la **estructura** solo la modula en una banda acotada, con la pendiente derivada del
efecto sobre el TAMAÑO del incendio (no ajustada contra la ignición).

**Limitaciones honestas (también en el propio visor → «Metodología y
limitaciones»):**
- **Pesos de combustible sin calibrar** — valores de partida (comportamiento
  publicado de Anderson).
- **`f_tiempo` real** desde los perímetros ICV/GVA (1993-2024): la validación
  histórica se hizo con etiquetas limpias, no con las semillas.
- **La exposición NO entra en el índice** — v1.1 separó peligro y exposición a
  propósito; la exposición se alimenta del módulo IUF (arriba), no de un proxy de
  población.
- El índice se calcula sobre meteo de reanálisis con ~5 días de latencia (no es
  tiempo real, T7); cada dato va etiquetado con su fecha.

---

## Por qué

Castellón ha sufrido cuatro eventos graves en cuatro años —Bejís (2022, ~19.000
ha), Les Useres (2022), Villanueva de Viver (2023, 4.700 ha) y la Serra d'Espadà
(2026)— mientras los planes de prevención de demarcación sobre los que se
planifica fueron redactados entre 2007 y 2013 y actualizados en 2013–2014.

## Visor

Los 135 términos de la provincia en un visor MapLibre servido por teselas
vectoriales propias (`ST_AsMVT` desde PostGIS, sin `pg_tileserv` ni token de
Mapbox). Coropleto por nivel de peligro; el estado dinámico (índice, nivel) viaja
por JSON aparte y se une en cliente por `ine_code` (las teselas declaran
`promoteId: 'ine_code'`, ADR-06), así que actualizar el índice a diario no
invalida las teselas, que se cachean un año.

<!-- Captura pendiente: docs/img/visor.png. Se genera levantando el stack con datos
     (make up && make seed) y fotografiando http://localhost:5173. No se incluye una
     imagen fabricada. -->
> _Captura del visor: pendiente de `docs/img/visor.png` — se obtiene con el stack
> levantado y sembrado (`make up && make seed`), sobre `http://localhost:5173`._

## Interfaz urbano-forestal (IUF)

Para cada una de las **188.215 edificaciones** (residencial + agrario) de los 135
municipios, cruza la geometría del **Catastro INSPIRE** con la capa forestal (PATFOR)
y clasifica según la **franja perimetral del Anexo XI del TRLOTUP** (30 m; **50 m si
la pendiente > 30 %**, muestreada del MDT25). El **Decreto 91/2023 art. 145** remite
a esa misma norma, sin fijar anchura propia (ambos textos citados literalmente desde
BOE/DOGV). Salida: agregado por municipio (**público**) e informe PDF por término
(**tras JWT**, solo del término autorizado). Detalle por edificación: solo autenticado.

**Provincia: el 10,2 % de las edificaciones no tiene la franja legal** (crítico +
incumple). Dos vistas complementarias:

| Top-10 por % sin franja (≥20 edif.) | % | | Top-10 por edif. CRÍTICAS (dentro del monte) | nº |
|---|---|---|---|---|
| Palanques | 93,2 | | la Vall d'Uixó | 230 |
| Higueras | 90,3 | | Onda | 181 |
| Castillo de Villamalefa | 76,6 | | l'Alcora | 162 |
| Pavías | 66,7 | | Llucena | 139 |
| Alfondeguilla | 65,1 | | Sierra Engarcerán | 124 |
| Alcudia de Veo | 64,3 | | Villahermosa del Río | 123 |
| Vallibona | 62,5 | | Culla | 121 |
| Xodos/Chodos | 58,8 | | Morella | 106 |
| Llucena | 56,3 | | Segorbe | 105 |
| Fuentes de Ayódar | 55,6 | | Ares del Maestrat | 103 |

El **%** encabeza pueblos de montaña diminutos (todo el núcleo en el monte); el
**crítico absoluto** encabeza pueblos grandes con más casas literalmente dentro del
monte — la vista más accionable para un ayuntamiento.

**Límites (escritos también en cada informe):** estimación geométrica automatizada,
**NO** una certificación de cumplimiento (corresponde al órgano competente). La
pendiente del MDT25 (25 m) suaviza el relieve → el análisis **subestima** la franja
de 50 m en ladera, no la sobreestima. Parte de geometría catastral con error
posicional (de ahí la «cautela técnica», que **no** es incumplimiento). No hay
`n_vias_evacuacion` (requeriría una capa de carreteras). La exposición es un **eje
aparte del peligro** y **no** vuelve al índice (v1.1 separó ambos ejes).

## Metodología (resumen)

- **FWI canadiense implementado desde las ecuaciones** de Van Wagner & Pickett
  (1985, FTR 33), no de una librería: reproduce la tabla de referencia de 49 días
  con `max|diff| < 0,1` en los seis códigos. Mediodía fijado a 12:00 UTC (criterio
  EFFIS); latitud de Castellón (40 °N) usa los coeficientes estándar canadienses.
- **Cadena continua.** Los códigos FWI son recursivos (arrastran humedad de días
  previos). La serie es UNA sola desde 2005-01-01: el backfill histórico y el job
  diario encadenan sin reinicio en la frontera (verificado: DC 13→14-ago sin reset).
  Los ~30 primeros días son «calentamiento» y se excluyen.
- **Meteo:** Open-Meteo ERA5-Seamless (reanálisis), fuente única para histórico y
  operación → sin desajuste de percentiles calibración↔evaluación. Latencia ~5 días.
- **Índice v1.1:** `comp_meteo_abs · modulador(comp_estructural)`. `comp_meteo_abs`
  es el percentil del FWI sobre la distribución **provincial** (conserva la magnitud
  estacional). El **modulador estructural** es lineal y acotado, con la pendiente
  **derivada del efecto de la estructura sobre el TAMAÑO** del incendio (Spearman
  0,616; extremo prudente del IC; amortiguado por la media geométrica), **no ajustada
  contra la ignición** (con 15 positivos sería sobreajuste). Los niveles 1–5 salen
  en cuasi-quintiles porque el índice es, por construcción, un percentil de peligro.

## Resultados de la validación (backtest, docs/09)

Positivos = pares (municipio, fecha) de incendios ≥ 100 ha (EGIF/MITECO 2005-2022);
negativos = el resto de la temporada; partición **temporal** (2005-2015 / 2016-2022,
nunca aleatoria); desbalance extremo → AUC-ROC + AUC-PR con IC por bootstrap.

**Sin maquillar:**

| Variante | AUC calib (n=15) | AUC valid (n=5) |
|---|---|---|
| FWI crudo (línea base) | **0,891** | 0,819 |
| Índice compuesto v1.0 | 0,767 | 0,752 |
| **Índice v1.1 (meteo abs × modulador)** | **0,864** | 0,823 |
| Baseline estacional (solo calendario) | 0,475 | **0,741** |

- **El compuesto original (v1.0) NO batía al FWI crudo.** No se esconde: se
  diagnosticó (el percentil estacional tiraba la magnitud; la vulnerabilidad era
  lastre neutro; la estructura sola es casi aleatoria para la *ignición*) y de ahí
  salió la forma v1.1, que sí alcanza al FWI crudo **conservando** la información de
  severidad (la estructura predice el TAMAÑO, Spearman 0,616).
- **El baseline estacional saca 0,741 en validación** — casi como el FWI crudo. Con
  5 positivos, casi todos de agosto, «es verano» ya separa casi tanto como el modelo:
  buena parte de la habilidad en validación es estacionalidad. El FWI lleva señal
  real más allá de la estación, visible en calibración (0,891 vs 0,475), donde hay
  más n.
- **Solo 20 positivos en ventana (15 / 5).** Los IC son anchos (validación ±0,25); el
  AUC-PR ≈ 0 por la tasa base (20 positivos frente a ~10⁶ pares). El índice sirve para
  **ordenar** días peligrosos, no para clasificar con un corte. Cuando lleguen los
  positivos 2023-2026 (registro GVA) la validación mejora. **Sin pesos calibrados por
  datos: la forma se decidió sobre la evidencia, no se ajustó a un AUC bonito.**

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
final** (`finalize`) calcula el FWI de los 135 sobre la serie completa, verifica
que la meteo está completa (aborta diciendo qué falta), corre las aserciones de
completitud y regenera el informe.

El lanzamiento va por `ops/backfill.sh`, con **salvaguardas**: un solo tramo a la
vez (rechaza lanzar otro con uno vivo), `espera` bloqueante, e intervalo mínimo
entre tramos (60 min; dos seguidos darían 429 de Open-Meteo). NO lances tramos a
mano en paralelo.

```bash
# Un tramo, desatendido (sobrevive a la desconexión SSH):
bash ops/backfill.sh tramo 2005 2008         # intermedio (solo meteo)
bash ops/backfill.sh espera                  # BLOQUEA hasta que acabe + veredicto
make backfill-check                          # valida lo ingerido (ver abajo)

# ...tras los tramos intermedios, el FINAL calcula FWI + aserciones + informe:
bash ops/backfill.sh tramo 2025 2026 true
bash ops/backfill.sh espera
```

**Ensayo en seco (antes del T1 real).** Valida la mecánica completa —guardas,
petición real, parseo, persistencia, `backfill-check`— sobre **1 año × 5
municipios**, sin comprometer la serie y trivial de deshacer:

```bash
bash ops/backfill.sh tramo-dry            # 5 municipios de control × año 2020
bash ops/backfill.sh tramo-dry            # (a la vez: debe RECHAZARSE por concurrencia)
bash ops/backfill.sh espera
GUAITA_BACKFILL_EXPECT_MUNIS=5 make backfill-check   # espera 5 municipios, no 135
docker stats guaita-backfill              # (durante el tramo) pico de memoria
bash ops/backfill.sh dry-limpia           # borra las filas del ensayo antes del T1
```

El log del ensayo trae `Open-Meteo 200: N KB`: multiplica por ~27 (135/5) para
estimar el payload de un año-petición real. Con eso mides el tamaño y el pico de
memoria en vez de estimarlos.

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

Memoria durante el tramo (riesgo de OOM al parsear el JSON horario; el heap va
capado con `-Xmx`, `ExitOnOutOfMemoryError` sale limpio si aun así revienta):

```bash
docker stats guaita-backfill        # en vivo; Ctrl-C para salir (--no-stream = una foto)
```

Reanudar tras un aborto/parada = relanzar el mismo tramo: es idempotente y
retoma el estado desde la BD (que ahora persiste en el volumen del VPS).

### Validación temprana — `make backfill-check`

La primera comprobación de correctitud NO puede esperar al tramo final: si el T1
ingiere con la hora equivocada o sin corrección altitudinal, hay que enterarse el
día 1, no el día 3 con la serie entera contaminada. **Corre `make backfill-check`
tras CADA tramo, antes de lanzar el siguiente** (sale con error si algo falla):

```bash
bash ops/backfill.sh tramo 2005 2008
bash ops/backfill.sh espera
make backfill-check      # <- aquí, antes del T2
```

Verifica sobre lo ingerido hasta ese momento: cobertura rectangular (135
municipios, todos con el mismo nº de días), `elevacion_celda_m`/`delta_altitud_m`
no NULL, rangos físicos (temp −20..50, HR 0..100, viento ≥ 0), estacionalidad
(un día de verano debe ser más cálido que uno de invierno; si no, hay un fallo de
índice horario / zona), y una tabla de los 6 municipios de control con celda,
altitud, delta y T/HR de un día de verano —Vistabella y Villahermosa con
corrección apreciable, los costeros casi nula—.

### Operación diaria (tras el backfill)

Terminado el backfill, un job `@Scheduled` (06:30 `Europe/Madrid`) ingiere cada
día la meteo nueva y recalcula el FWI. **Recupera huecos**: no procesa "hoy",
sino desde la última fecha con dato hasta el corte del archivo (~D-5); si el
servidor estuvo caído una semana, la siguiente pasada rellena los siete días. Si
Open-Meteo falla o los datos no validan, **no escribe nada** (ni ceros) y el hueco
lo recupera la pasada siguiente.

Está **apagado por defecto**; actívalo SOLO cuando el histórico esté completo (si
no, generaría cadenas parciales):

```bash
# en .env:  GUAITA_SCHEDULER_ENABLED=true   (luego reinicia la api)
docker compose -f docker-compose.yml -f docker-compose.vps.yml up -d --wait api

# Métrica de retraso respecto a D-5 (si crece, algo va mal aunque el job no falle):
docker compose -f docker-compose.yml -f docker-compose.vps.yml exec db \
  psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc \
  "select (current_date - 5) - max(fecha) from meteo_municipio;"
```

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
| [09](docs/09-validacion.md) | Metodología y resultados del backtest |
| [10](docs/10-aviso-legal.md) | Aviso legal y privacidad |

## Datos y atribución

NASA FIRMS · AEMET OpenData · Copernicus EFFIS · PATFOR (Generalitat Valenciana)
· CNIG/IGN · Dirección General del Catastro (INSPIRE).

## Licencia

MIT. Ver [LICENSE](LICENSE).
