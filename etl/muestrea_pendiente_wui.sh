#!/usr/bin/env bash
# Muestrea la pendiente (%) del ráster gdaldem (slope_pct.tif, 25830) en el punto representativo de
# cada edificación -> edificacion.pendiente_pct. La norma (Anexo XI TRLOTUP) exige franja de 50 m
# donde la pendiente > 30 % (30 m en el resto), así que la franja se aplica POR EDIFICACIÓN.
set -euo pipefail
: "${PGHOST:?}"; : "${PGDATABASE:?}"; : "${PGUSER:?}"; : "${PGPASSWORD:?}"
SLOPE="${SLOPE:-/data/slope_pct.tif}"
[ -f "$SLOPE" ] || { echo "no existe el ráster de pendiente $SLOPE"; exit 1; }
cd /tmp

echo "==> Exportando puntos de edificación…"
psql -tAc "select ref_catastral||','||st_x(p)||','||st_y(p)
           from (select ref_catastral, st_pointonsurface(geom) p from edificacion order by ref_catastral) t" > pts.csv
n_pts=$(wc -l < pts.csv)
echo "    $n_pts edificaciones."

echo "==> Muestreando pendiente con gdallocationinfo…"
# gdallocationinfo -valonly -geoloc lee 'x y' por línea y saca un valor por línea, EN ORDEN.
cut -d, -f2,3 pts.csv | tr ',' ' ' | gdallocationinfo -valonly -geoloc "$SLOPE" > vals.txt
n_vals=$(wc -l < vals.txt)
if [ "$n_pts" -ne "$n_vals" ]; then
  echo "DESALINEADO: $n_pts puntos vs $n_vals valores. Aborto (no se puede casar ref<->pendiente)."; exit 1
fi
paste -d, <(cut -d, -f1 pts.csv) vals.txt > ref_slope.csv

echo "==> Cargando pendiente en edificacion…"
psql -v ON_ERROR_STOP=1 <<'SQL'
\set QUIET on
begin;
drop table if exists stg_slope;
create temp table stg_slope (ref text, slope numeric);
\copy stg_slope from 'ref_slope.csv' with (format csv, null '')
update edificacion e set pendiente_pct = s.slope
from stg_slope s where s.ref = e.ref_catastral;
do $$
declare sin int; mx numeric;
begin
  select count(*) into sin from edificacion where pendiente_pct is null;
  select max(pendiente_pct) into mx from edificacion;
  raise notice 'pendiente: % edificaciones sin dato (fuera del ráster), pendiente máx=% %%', sin, round(mx);
end $$;
commit;
SQL
echo "==> pendiente_pct: OK."
