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

# Pendiente en el commit siguiente de la Fase 1 (idempotente):
#   [3/3] ./load_topografia.sh  (MDT25 + gdaldem slope + estadísticos zonales)
echo "==> seed: municipios + terreno forestal + combustible cargados. Capa 3/3 pendiente."
