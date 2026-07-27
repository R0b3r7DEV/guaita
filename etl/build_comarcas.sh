#!/usr/bin/env bash
# make comarcas — regenera etl/mappings/comarcas_castellon.csv desde la fuente
# oficial (PEGV). DELIBERADO y con red; NO lo llama make seed (que solo LEE el
# CSV committeado). Si la comarcalización cambia, se ve en un diff de git.
set -euo pipefail

PEGV_URL="${PEGV_URL:-https://pegv.gva.es/documents/163706934/379210449/Municipios+y+comarcas+a+1+de+enero+de+2026.xlsx/a5622ee2-bea1-643f-d4b7-a498900932ef?t=1780295046805}"
PEGV_FECHA="1 de enero de 2026"
XLSX=/data/pegv_municipios_comarcas.xlsx
OUT=/etl/mappings/comarcas_castellon.csv

mkdir -p /data /etl/mappings
echo "==> Descargando relación municipios-comarcas del PEGV…"
curl -fsSL -o "$XLSX" "$PEGV_URL"
echo "==> Parseando XLSX -> CSV (135 municipios de Castellón)…"
python3 /etl/parse_comarcas.py "$XLSX" "$OUT" "$PEGV_URL" "$PEGV_FECHA"
echo "==> Hecho. Revisa el diff de $OUT y commitéalo."
