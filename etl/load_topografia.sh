#!/usr/bin/env bash
# Carga de topografía municipal desde el MDT25 (Fase 1). Llamado por seed.sh.
# Idempotente. Consume las 22 teselas del RELEASE (data/mdt25-v1), NUNCA del
# CNIG: esa ruta es exclusiva de `make mdt-fetch`.
#
# Orden obligatorio (ver las 3 precisiones):
#  (1) MOSAICO antes de la pendiente: gdalbuildvrt de las 22 -> gdaldem sobre el
#      mosaico. Calcular por tesela y mosaicar daría artefactos en los bordes.
#  (2) gdaldem slope -p -> PORCENTAJE, no grados (45°=100%). El MDT y 25830 están
#      ambos en metros, así que unidades V y H coinciden: NO hace falta -s.
#  (3) NoData -32767 DECLARADO en el VRT: el mar es NoData, así gdaldem no calcula
#      pendientes falsas tierra<->mar en la costa (Nules/Moncofa/Almenara).
set -euo pipefail

DATA=/data
MDT="$DATA/mdt25"
TARBALL="$DATA/mdt25-castellon.tar.gz"
RELEASE_URL="https://github.com/R0b3r7DEV/guaita/releases/download/data/mdt25-v1/mdt25-castellon.tar.gz"
NODATA=-32767
VRT="$DATA/mdt25.vrt"
SLOPE="$DATA/slope_pct.tif"
ASPECT="$DATA/aspect.tif"
ZONE="$DATA/zone.tif"
PG="host=$PGHOST port=$PGPORT dbname=$PGDATABASE user=$PGUSER password=$PGPASSWORD"
run_sql() { psql -v ON_ERROR_STOP=1 -q "$@"; }
tif_count() { ls "$MDT"/PNOA-MDT25-ETRS89-HU30-*.TIF 2>/dev/null | wc -l; }

mkdir -p "$MDT"

# --- 1. Teselas desde el Release (idempotente; NUNCA CNIG) --------------------
if [[ "$(tif_count)" -ne 22 ]]; then
  echo "==> Descargando MDT25 del Release (data/mdt25-v1)…"
  curl -fsSL -o "$TARBALL" "$RELEASE_URL" \
    || { echo "ERROR: no pude bajar el tar.gz del Release. Publícalo con make mdt-fetch + gh release. NO se usa el CNIG como fallback." >&2; exit 1; }
  tar -xzf "$TARBALL" -C "$MDT"
fi
if [[ ! -f "$MDT/SHA256SUMS" ]]; then
  echo "ERROR: falta $MDT/SHA256SUMS (¿tar.gz incompleto?)." >&2; exit 1
fi
echo "==> Verificando SHA256SUMS del MDT25…"
( cd "$MDT" && sha256sum -c SHA256SUMS ) >/dev/null \
  || { echo "ERROR: checksum del MDT25 NO cuadra. Aborto (no uso datos sin verificar)." >&2; exit 1; }
[[ "$(tif_count)" -eq 22 ]] || { echo "ERROR: $(tif_count) teselas, esperaba 22." >&2; exit 1; }
echo "    22 teselas verificadas."

# --- 2. Mosaico VRT con NoData declarado -------------------------------------
echo "==> Mosaico (gdalbuildvrt, NoData=$NODATA)…"
gdalbuildvrt -q -srcnodata "$NODATA" -vrtnodata "$NODATA" \
  "$VRT" "$MDT"/PNOA-MDT25-ETRS89-HU30-*.TIF

# --- 3. Pendiente (%) y orientación SOBRE EL MOSAICO -------------------------
echo "==> gdaldem slope -p (porcentaje) y aspect sobre el mosaico…"
# -compute_edges: calcula pendiente/orientación en bordes y junto a NoData usando
# SOLO los vecinos válidos (ignora el NoData). Así la franja costera de tierra
# obtiene pendiente real en vez de perderse, y sin el artefacto tierra<->mar
# (el NoData no entra en el cálculo). -p: %, no grados. Sin -s (V y H en metros).
gdaldem slope -p -compute_edges "$VRT" "$SLOPE"  -q
gdaldem aspect   -compute_edges "$VRT" "$ASPECT" -q     # grados; NoData en llanos.

# --- 4. Rasterizar municipios en la MISMA rejilla que el mosaico -------------
echo "==> Rasterizando municipios (zona) alineado al mosaico…"
ogr2ogr -f GPKG "$DATA/muni_zone.gpkg" "PG:$PG" \
  -sql "select (ine_code::int) as ine_int, geom from municipio" -nln muni -overwrite
read -r COLS ROWS ULX ULY LRX LRY < <(python3 - "$VRT" <<'PY'
import sys; from osgeo import gdal
ds=gdal.Open(sys.argv[1]); gt=ds.GetGeoTransform(); c=ds.RasterXSize; r=ds.RasterYSize
print(c, r, gt[0], gt[3], gt[0]+c*gt[1], gt[3]+r*gt[5])
PY
)
gdal_rasterize -q -a ine_int -ot Int32 -init 0 -a_nodata 0 -at \
  -te "$ULX" "$LRY" "$LRX" "$ULY" -ts "$COLS" "$ROWS" \
  "$DATA/muni_zone.gpkg" "$ZONE"
# -at (ALL_TOUCHED): todo municipio recibe los píxeles que TOCA; sin él, un
# término pequeño sin centro de píxel dentro se quedaba fuera (134 de 135).

# --- 5. Estadísticos zonales -> CSV ------------------------------------------
echo "==> Estadísticos zonales por municipio…"
python3 /etl/zonal_topografia.py "$VRT" "$SLOPE" "$ASPECT" "$ZONE" "$DATA/topografia.csv"

# --- 6. Carga idempotente + aserciones ---------------------------------------
echo "==> Cargando topografia_municipio + aserciones…"
psql -v ON_ERROR_STOP=1 <<SQL
\set QUIET on
begin;
create temp table tmp_topo(ine_code text, pendiente_media_pct numeric,
  pendiente_p90_pct numeric, frac_solana numeric, altitud_media_m numeric);
\copy tmp_topo from '$DATA/topografia.csv' with (format csv, header true)

truncate topografia_municipio;
insert into topografia_municipio
  (ine_code, pendiente_media_pct, pendiente_p90_pct, frac_solana, altitud_media_m)
select ine_code, pendiente_media_pct, pendiente_p90_pct, frac_solana, altitud_media_m
from tmp_topo;

do \$\$
declare n int;
begin
  select count(*) into n from topografia_municipio;
  if n <> 135 then raise exception 'topografia_municipio tiene % filas, esperaba 135', n; end if;

  select count(*) into n from topografia_municipio
   where pendiente_media_pct is null or pendiente_p90_pct is null
      or frac_solana is null or altitud_media_m is null;
  if n <> 0 then raise exception '% filas con algún valor NULL', n; end if;

  -- p90 >= media en todos (si no, error en el percentil). (También CHECK en V6.)
  select count(*) into n from topografia_municipio where pendiente_p90_pct < pendiente_media_pct;
  if n <> 0 then raise exception '% municipios con p90 < media', n; end if;

  -- Plausibilidad por conocimiento externo (ine_code verificados en el CSV):
  --   Llanos costeros (Plana):  Nules 12082, Moncofa 12077, Almenara 12011.
  --   Inclinados de interior:   Vistabella 12139, Villahermosa 12130, Morella 12080.
  -- Los tres llanos deben tener p90 bajo; los tres inclinados, alto. Si un
  -- costero sale escarpado (artefacto de costa/NoData) o un serrano sale llano
  -- (mosaico/unidades mal), salta.
  select count(*) into n from topografia_municipio
   where ine_code in ('12082','12077','12011') and pendiente_p90_pct >= 40;
  if n <> 0 then raise exception '% municipios llanos (Plana) con p90>=40%%: ¿artefacto de costa?', n; end if;

  select count(*) into n from topografia_municipio
   where ine_code in ('12139','12130','12080') and pendiente_p90_pct <= 30;
  if n <> 0 then raise exception '% municipios de montaña con p90<=30%%: ¿mosaico/unidades mal?', n; end if;

  -- Vistabella del Maestrat: altitud media > 1000 m (está a los pies de Penyagolosa).
  if (select altitud_media_m from topografia_municipio where ine_code='12139') < 1000 then
    raise exception 'Vistabella (12139) con altitud media < 1000 m: revisar'; end if;
end \$\$;
commit;
SQL
echo "==> topografia_municipio: OK."

# --- 7. Informe con los 6 municipios de control ------------------------------
echo "==> Municipios de control (llanos vs inclinados):"
run_sql -P pager=off -c "
select m.ine_code, m.nombre,
       t.pendiente_media_pct as med_pct, t.pendiente_p90_pct as p90_pct,
       t.frac_solana, t.altitud_media_m as alt_m
from topografia_municipio t join municipio m using (ine_code)
where m.ine_code in ('12082','12077','12011','12139','12130','12080')
order by t.pendiente_p90_pct;"
