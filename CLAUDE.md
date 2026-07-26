# GUAITA — Contexto para Claude Code

> `guaita` (valenciano): atalaya, torre de vigía. Las torres desde las que
> históricamente se vigilaba el monte en el Maestrat y Espadán.

Sistema de inteligencia de riesgo de incendio forestal para la provincia de
Castellón. Calcula un índice de peligro diario por término municipal a partir de
datos abiertos, detecta viviendas en interfaz urbano-forestal sin franja de
protección legal, y valida el índice contra el histórico real de incendios.

**No es un sistema de emergencias.** No sustituye al 112 ni al PREVIFOC oficial.
Es una herramienta analítica y de portafolio. Este aviso debe aparecer en la UI.

---

## Stack

| Capa | Tecnología | Motivo |
|---|---|---|
| Backend | Java 21 + Spring Boot 4.1 | Stack conocido del autor (XPL0DAY); 4.1 es la GA soportada (T8) |
| BD | PostgreSQL 16 + PostGIS 3.4 | Geoprocesado en BD, no en app |
| Migraciones | Flyway | Versionado de esquema, incluye SQL espacial |
| ETL pesado | GDAL/ogr2ogr en contenedor Python 3.12 | Cargar shapefiles sin GeoTools |
| Frontend | React 19 + Vite + TypeScript | Stack conocido del autor (Cuentia) |
| Mapas | MapLibre GL JS | Open source, sin token ni cuota |
| Tiles vectoriales | Endpoint MVT propio (ADR-04, sin `pg_tileserv`) | `ST_AsMVT` desde PostGIS |
| Infra | Docker Compose | VPS propio con Apache como reverse proxy |
| Tests | JUnit 5 + Testcontainers | Tests de integración con PostGIS real |

## Comandos

```bash
make up            # docker compose up -d --build --wait (db, api, web); bloquea hasta healthy
make down
make seed          # carga geodatos estáticos (municipios, MDT, PATFOR)
make ingest        # fuerza una pasada de ingesta de feeds vivos
make test          # ./gradlew test  (Testcontainers levanta PostGIS)
make migrate       # flyway migrate
make lint          # spotless + eslint
make backtest      # ejecuta la validación histórica del índice
```

## Estructura

```
/api          Spring Boot. Módulos: ingest, risk, wui, alerts, web(controllers)
/etl          Scripts Python/GDAL para carga de geodatos estáticos
/web          React + Vite
/docs         Documentación viva. LEER ANTES DE IMPLEMENTAR.
/data         Descargas locales (gitignored, puede pesar GB)
```

**No hay `/db` en la raíz.** Las migraciones Flyway viven en
`api/src/main/resources/db/migration` (convención de Spring): cero configuración,
funcionan con Testcontainers sin tocar nada y viajan dentro del jar, así que el
artefacto desplegado es autocontenido. Ponerlas en `/db` obligaría a un
`spring.flyway.locations` con `filesystem:` y rompería el jar empaquetado.

## Reglas de trabajo

**Antes de implementar cualquier módulo, lee el documento correspondiente en
`/docs`.** El índice de peligro y el módulo de interfaz urbano-forestal tienen
especificación técnica cerrada; no improvisar la fórmula.

1. **Secretos fuera del cliente, siempre.** Las claves de NASA FIRMS y AEMET
   OpenData viven en variables de entorno del backend. El frontend nunca ve una
   clave. Si un endpoint externo hace falta desde el navegador, se hace proxy
   desde Spring. (Ver `docs/07-seguridad.md`.)
2. **Geoprocesado en PostGIS, no en Java.** Buffers, intersecciones y
   agregaciones son SQL. Java orquesta, no calcula geometría.
3. **Todo dato tiene procedencia.** Cada tabla de hechos lleva `source`,
   `fetched_at` y `source_url`. Si no se puede citar, no entra.
4. **SRID único: 25830** (ETRS89 / UTM 30N) para cálculo métrico.
   4326 solo en la frontera de la API. Nunca calcular distancias en 4326.
5. **Idempotencia.** Toda ingesta debe poder re-ejecutarse sin duplicar.
   Clave natural + `ON CONFLICT DO UPDATE`.
6. **Nada de `SELECT *` en código de producción.** Columnas explícitas.
7. **Comentarios y nombres de dominio en castellano; código en inglés.**
   Tabla `municipio`, columna `superficie_forestal_ha`, clase `RiskIndexService`.

## Convenciones de código

- Java: Google Java Format vía Spotless. Records para DTOs. `Optional` en
  retornos de repositorio, nunca en parámetros ni campos.
- No lombok. Records + constructores explícitos.
- Frontend: componentes funcionales, hooks. Sin `any`. Zod para validar
  respuestas de la API en el borde.
- Commits: Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:`).
- Ramas: `main` protegida, trabajo en `feat/…`.

## Errores conocidos que no hay que repetir

- El autor ya sufrió una fuga de API key por llamar a un servicio externo desde
  el frontend. Ese patrón está prohibido en este repositorio sin excepción.
- Los shapefiles del PATFOR vienen en ETRS89 pero no siempre declaran el SRID.
  Forzar con `ogr2ogr -a_srs EPSG:25830` si `-t_srs` falla.
- FIRMS devuelve CSV con cabecera; una petición sin resultados devuelve **solo**
  la cabecera, no un 404. Tratar 0 filas como caso válido, no como error.

## Estado actual

Fase 0. Nada implementado. Ver `docs/08-roadmap.md` para el orden de ataque.
