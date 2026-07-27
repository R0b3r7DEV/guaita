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

# Las capas se cargan en los commits siguientes de la Fase 1, cada una en su
# script y de forma idempotente:
#   ./load_municipios.sh       (límites CNIG + comarcas + padrón INE)
#   ./load_terreno_forestal.sh (PATFOR, ST_Subdivide)
#   ./load_topografia.sh       (MDT25 + gdaldem slope + estadísticos zonales)
echo "==> Contenedor ETL operativo. Aún sin capas cargadas (ver commits de Fase 1)."
