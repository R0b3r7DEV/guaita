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

## Stack

Java 21 · Spring Boot 3.3 · PostgreSQL 16 + PostGIS 3.4 · React 19 + Vite ·
MapLibre GL · Docker Compose

## Arranque

```bash
cp .env.example .env     # rellenar FIRMS_MAP_KEY y AEMET_API_KEY
make up
make seed                # geodatos base (tarda; descarga varios GB)
make ingest              # primera pasada de feeds
```

Visor en `http://localhost:5173`, API en `http://localhost:8080/api/v1`.

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

Por decidir (MIT o AGPL-3.0).
