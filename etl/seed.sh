#!/usr/bin/env bash
# GUAITA — carga de geodatos estáticos (Fase 1). Orquestador de `make seed`.
# Idempotente: correrlo dos veces seguidas debe dejar la BD igual.
# Se ejecuta DENTRO del contenedor etl (ver docker-compose.yml, profile "etl").
set -euo pipefail

# Variables de conexión: las inyecta compose (PGHOST/PGDATABASE/PGUSER/PGPASSWORD).
# psql y el driver PG de ogr2ogr las leen del entorno.
: "${PGHOST:?falta PGHOST}"
: "${PGDATABASE:?falta PGDATABASE}"
: "${PGUSER:?falta PGUSER}"
: "${PGPASSWORD:?falta PGPASSWORD}"

echo "==> Verificando conexión a PostGIS…"
psql -v ON_ERROR_STOP=1 -tAc "select postgis_version();" >/dev/null
echo "    conexión OK."

echo "==> [1/3] Municipios…"
bash /etl/load_municipios.sh

echo "==> [2/3] Terreno forestal (PATFOR)…"
bash /etl/load_terreno_forestal.sh
echo "==> [2/3] Modelo de combustible (PATFOR, Anderson)…"
bash /etl/load_modelo_combustible.sh

echo "==> [3/4] Topografía (MDT25: pendiente, orientación, altitud)…"
bash /etl/load_topografia.sh

echo "==> [4/4] Perímetros históricos de incendio (semillas + EFFIS si hay fichero)…"
bash /etl/load_incendios.sh

echo "==> seed: COMPLETA (municipios + forestal + combustible + topografía + incendios)."
