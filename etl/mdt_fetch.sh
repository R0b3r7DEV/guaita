#!/usr/bin/env bash
# make mdt-fetch — VERIFICA y EMPAQUETA las 22 teselas MDT25 (Opción C).
#
# NO descarga nada. Las teselas las bajó el operador a mano a data/mdt25/
# siguiendo etl/reports/mdt25-descarga.md (el MDT es dato estático anual;
# automatizar un flujo con estado del CNIG para eso es mal negocio y frágil).
# Este script es el paso DELIBERADO que valida y empaqueta para publicar como
# asset de Release; el seed luego consume del Release, nunca del CNIG.
#
# Corre en el contenedor etl (tiene gdal): `make mdt-fetch`. Idempotente.
set -euo pipefail

DIR=/data/mdt25
ATRIB="MDT25 1ª cobertura CC-BY 4.0 ign.es — © Instituto Geográfico Nacional (IGN)/CNIG"
# Las 22 hojas MTN50 del Castellón continental (ver la tabla de descarga).
SHEETS="0519 0520 0521 0544 0545 0546 0547 0569 0570 0571 0591 0592 0593 0594 0614 0615 0616 0639 0640 0641 0667 0668"

cd "$DIR" 2>/dev/null || { echo "ERROR: no existe $DIR (¿bajaste las teselas?)." >&2; exit 1; }

fichero() { echo "PNOA-MDT25-ETRS89-HU30-${1}-LID.TIF"; }

# --- 1. Las 22 teselas, con el nombre esperado (HU30 = 25830) -----------------
missing=()
for s in $SHEETS; do [[ -f "$(fichero "$s")" ]] || missing+=("$(fichero "$s")"); done
if [[ ${#missing[@]} -gt 0 ]]; then
  echo "ERROR: faltan ${#missing[@]} de 22 teselas. 22 o nada:" >&2
  printf '   %s\n' "${missing[@]}" >&2
  echo "Descárgalas según etl/reports/mdt25-descarga.md." >&2
  exit 1
fi
alt="$(echo "$SHEETS" | tr ' ' '|')"
extra=$(ls PNOA-MDT25-*.TIF 2>/dev/null | grep -vE "HU30-(${alt})-LID\.TIF$" || true)
[[ -n "$extra" ]] && { echo "AVISO: teselas MDT25 no esperadas en $DIR:"; echo "$extra"; }

# --- 2. Cada una en EPSG:25830 (el criterio DEFINITIVO) ----------------------
echo "==> Verificando EPSG:25830 de las 22 teselas (gdalsrsinfo)…"
bad=()
for s in $SHEETS; do
  f="$(fichero "$s")"
  epsg=$(gdalsrsinfo -o epsg "$f" 2>/dev/null | grep -oE '[0-9]{4,5}' | head -1)
  [[ "$epsg" == "25830" ]] || bad+=("$f -> EPSG:${epsg:-desconocido}")
done
if [[ ${#bad[@]} -gt 0 ]]; then
  echo "ERROR: teselas que NO están en EPSG:25830 (variante equivocada):" >&2
  printf '   %s\n' "${bad[@]}" >&2
  echo "Las de huso 31 deben ser la variante «huso 30 extendido» (ver la tabla)." >&2
  exit 1
fi
echo "    las 22 en EPSG:25830. OK."

# --- 3. SHA256SUMS -----------------------------------------------------------
echo "==> Generando SHA256SUMS…"
files=(); for s in $SHEETS; do files+=("$(fichero "$s")"); done
sha256sum "${files[@]}" > SHA256SUMS

# --- 4. Procedencia ----------------------------------------------------------
cat > PROVENANCE.txt <<EOF
MDT25 — Castellón continental (22 hojas MTN50)
Producto:    MDT25 1ª cobertura (PNOA-MDT25). codSerie CNIG: 02107.
Fuente:      Centro de Descargas del CNIG / Instituto Geográfico Nacional.
Empaquetado: $(date -u +%Y-%m-%d)
Licencia:    CC-BY 4.0 (Orden FOM/2807/2015).
Atribución:  ${ATRIB}
Variantes:   huso 30 nativo; huso 31 en «huso 30 extendido» -> todo EPSG:25830.
Hojas:       ${SHEETS}
Reproducir:  ver etl/reports/mdt25-descarga.md (descarga manual paso a paso).
EOF

# --- 5. Empaquetar ------------------------------------------------------------
echo "==> Empaquetando mdt25-castellon.tar.gz…"
tar --sort=name -czf mdt25-castellon.tar.gz PROVENANCE.txt SHA256SUMS "${files[@]}"
echo "==> OK: $DIR/mdt25-castellon.tar.gz ($(du -h mdt25-castellon.tar.gz | cut -f1))"
echo "    Publícalo en el Release 'data/mdt25-v1' (comandos aparte)."
