# 08 · Roadmap

Estimaciones para **un desarrollador a tiempo parcial** (~10–12 h/semana),
compaginando FCT, turnos rotativos y otros proyectos. Son realistas, no
optimistas.

Cada fase termina en algo **desplegado y funcionando**. Nada de "termino el
backend y luego el front".

---

## Fase 0 · Cimientos (semana 1) — ✅ COMPLETADA

- [x] Repo, `.gitignore`, `.env.example`, licencia (MIT).
- [x] `docker-compose.yml`: `db` (postgis/postgis:16-3.4), `api`, `web`.
- [x] Spring Boot 4.1 + Flyway + `V1__extensions.sql` (postgis, citext, pgcrypto).
- [x] Vite + React + TS arrancando.
- [x] CI en GitHub Actions: build + test + gitleaks.
- [x] `make up` levanta todo y `/actuator/health` responde.

**Criterio de aceptación:** un `git clone` + `make up` en limpio funciona.

**Validado automáticamente en CI** por el job `smoke`: genera un `.env`, ejecuta
`make up` (con `--wait`, el código de salida es el veredicto) y comprueba que
`/actuator/health` devuelve `status: UP`. No es una afirmación manual: cada push
lo reejecuta.

---

## Fase 1 · Geodatos base (semanas 2–3) — ✅ COMPLETADA

- [x] `etl/` con GDAL en contenedor (perfil `etl`, `make seed`).
- [x] Carga de límites municipales del CNIG → tabla `municipio` (135 registros).
- [x] Carga de la capa forestal PATFOR → `terreno_forestal`, con `ST_Subdivide`.
- [x] MDT → `gdaldem slope` → estadísticos zonales → `topografia_municipio`.
      (MDT25 servido como Release, consumo offline con SHA256; ver `etl/`.)
- [x] Endpoint `/tiles/municipios/{z}/{x}/{y}.mvt` con `ST_AsMVT` (ADR-04).
- [x] Visor MapLibre pintando la provincia (los 135 términos, solo geometría).

**Criterio:** se ve el mapa de Castellón con los 135 términos y se puede hacer
clic en uno. **Cumplido.**

**Decisión de arquitectura tomada aquí (ADR-06):** las teselas llevan solo
geometría e identidad y son inmutables; el índice de peligro (Fase 3) viajará por
JSON aparte y se unirá en cliente por `ine_code`. Así la caché nunca servirá el
peligro de ayer.

**Validado en CI:** el job `backend` corre el test de integración que decodifica
el MVT (la feature de Castelló viaja con sus atributos), y el job `smoke` pide
una tesela al stack levantado y comprueba `200` + `Content-Type`
`application/vnd.mapbox-vector-tile`.

**Riesgo (materializado y resuelto):** los shapefiles trajeron las sorpresas
previstas —proyección (teselas UTM que no cubrían el borde este de Moncofa),
codificación (ç, apóstrofes valencianos)— y se cerraron con aserciones que fallan
ruidosamente en el seed.

---

## Fase 2 · Meteorología y FWI (semanas 4–5) — ✅ COMPLETADA (ejecución incluida)

- [x] Migración V7: tablas `meteo_municipio` y `fwi_municipio`.
- [x] `FwiCalculator` desde Van Wagner & Pickett 1985 (ecuaciones exactas).
- [x] **Tests con vectores de referencia**: reproduce la tabla de ejemplo de la
      publicación (49 días) con `max|diff| < 0,1` en los seis códigos. (Contraste
      cffdrs en R: cobertura pendiente, sin R en el entorno; ver docs/04.)
- [x] **Fuente única Open-Meteo ERA5-Seamless** (ADR-07), para histórico Y
      operación. **AEMET NO es entrada de Fase 2: se mueve a Fase 4 como
      contraste externo** (ADR-07). El cliente AEMET no se implementa aquí.
- [x] Asignación por municipio desde la rejilla de reanálisis + corrección
      altitudinal **delegada en Open-Meteo** (`elevation=altitud_media_m`;
      verificado empíricamente que ya downscalea T y HR, docs/04). La calidad del
      dato pasó de `interpolado`/`n_estaciones` a `elevacion_celda_m` /
      `delta_altitud_m` (migración V8).
- [x] Backfill del histórico: **código** reanudable e idempotente, cadena FWI
      continua, flag `finalize`, aserciones de completitud e informe. Corre en el
      **VPS** contra BD persistente (la de Actions es efímera, docs/01), con
      guardia de disco y salvaguardas de concurrencia/intervalo (`ops/backfill.sh`).
- [x] Job `@Scheduled` diario: ingiere hasta el corte del archivo (~D-5),
      recupera huecos (no procesa "hoy"), zona horaria `Europe/Madrid` explícita,
      no escribe ante fallo de fuente. **ENCENDIDO** (`GUAITA_SCHEDULER_ENABLED=true`)
      tras completar el backfill.
- [x] **Backfill provincial EJECUTADO** en el VPS: 6 tramos de 3 años a razón de
      1/día (límite diario de Open-Meteo, ver docs/02), ~5-6 días naturales. Serie
      FWI **135 municipios × 2005-01-01 → 2026-08-13 = 1.065.825 filas**,
      rectangular, aserciones verdes.

**Criterio — CUMPLIDO.** `fwi_municipio` tiene la serie completa de los 135
municipios y el job diario añade la fila nueva sin intervención. Validación **a
escala provincial** (`etl/reports/fwi-backfill.md`): los 10 incendios reales caen
en la cola alta de su ventana ±15 días (P92.9–P100; Villanueva de Viver marzo-2023
P95.5); el gradiente de julio por comarca refleja altitud/continentalidad (valles
interiores cálidos y costa arriba, montaña del Maestrat/Ports abajo).

**Esta es la fase técnicamente más exigente.** Si algo se atasca, es aquí.

---

## Fase 3 · Índice compuesto y visor (semanas 6–7)

- [ ] Componente estructural: fracción forestal, continuidad, modelo de
      combustible, pendiente, `f_tiempo` desde el último incendio.
- [ ] Componente de vulnerabilidad (versión provisional sin IUF).
- [ ] Índice compuesto, niveles, banderas `regla303030` y `vientoAlineado`.
- [ ] Endpoints `/municipios` y `/municipios/{ineCode}`.
- [ ] Visor coropleto por nivel, panel de detalle, serie de 30 días.
- [ ] **Aviso permanente de "no es un sistema de emergencia".**
- [ ] Despliegue en el VPS con HTTPS.

**Criterio:** URL pública funcionando. Ya se puede enseñar. **Aquí el proyecto
deja de ser una promesa.** Si hubiera que parar por falta de tiempo, este es un
punto de parada digno.

---

## Fase 4 · Validación (semana 8)

Ver `docs/09-validacion.md`. No es opcional: es lo que separa esto de un
dashboard.

- [ ] Carga de `incendio_historico` desde EFFIS (20 años).
- [ ] Backtest: ROC/AUC del índice contra días de gran incendio.
- [ ] Calibración de `f_tiempo` y de los pesos.
- [ ] Página pública `/metodologia` con resultados y limitaciones.

**Criterio:** AUC ≥ 0,75 documentado y reproducible con `make backtest`.

---

## Fase 5 · Interfaz urbano-forestal (semanas 9–11)

- [ ] ETL del Catastro INSPIRE por municipio.
- [ ] Algoritmo de franja perimetral en PostGIS.
- [ ] Clasificación y agregados por término.
- [ ] Informe PDF con descargo de responsabilidad.
- [ ] JWT + control de acceso al detalle.
- [ ] Realimentar `comp_vulnerab` con los datos reales de IUF.

**Criterio:** informe descargable de un municipio de prueba (sugerencia:
Alfondeguilla o Eslida, pequeños y con interfaz clara).

---

## Fase 6 · Alertas y cierre (semana 12)

- [ ] Suscripciones con doble opt-in, baja en un clic.
- [ ] Envío diario por umbral.
- [ ] Política de privacidad y aviso legal.
- [ ] README con capturas y explicación metodológica.
- [ ] 30 días de operación sin intervención manual.

---

## Orden de sacrificio

Si el tiempo aprieta, se recorta en este orden:

1. Alertas por correo (fase 6) — bonito, no esencial.
2. Informe PDF — basta con la vista web.
3. Módulo IUF completo — se puede entregar solo para 5 municipios piloto.
4. Backtest — **no sacrificar.** Sin validación el proyecto pierde su argumento.
5. FWI — **jamás sacrificar.** Es el núcleo.

## Advertencia de alcance

Este proyecto tiene el tamaño justo para ser impresionante y el tamaño justo para
no terminarse nunca si se le añade algo. La tentación va a ser meter simulación
de propagación, o detección de humo, o cobertura de toda España. **No.** Un
proyecto terminado sobre una provincia vale infinitamente más que uno a medias
sobre el país.
