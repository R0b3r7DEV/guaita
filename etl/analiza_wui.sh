#!/usr/bin/env bash
# Análisis de franja perimetral IUF (docs/05) ajustado a la NORMA (Anexo XI TRLOTUP) -> wui_edificacion.
#
# FRANJA POR EDIFICACIÓN según el Anexo XI, punto 1: mínima de 30 m, "alcanzando, como mínimo, los 50
# metros cuando la pendiente sea superior al 30 %". La pendiente por edificación está en
# edificacion.pendiente_pct (muestrea_pendiente_wui.sh). CLASES reencuadradas a la norma: "incumple"
# SOLO para dist < franja (lo que la norma considera incumplimiento); "cumple" para dist >= franja
# (legalmente cumple); "advertencia_margen" es una CAUTELA TÉCNICA por el error de la geometría
# catastral (cumple pero cerca), NO un incumplimiento. Sin ST_Union (terreno_forestal ya subdividido).
set -euo pipefail
: "${PGHOST:?}"; : "${PGDATABASE:?}"; : "${PGUSER:?}"; : "${PGPASSWORD:?}"

FRANJA_BASE="${FRANJA_BASE:-30}"          # Anexo XI: mínima 30 m
FRANJA_PENDIENTE="${FRANJA_PENDIENTE:-50}" # Anexo XI: 50 m si pendiente > umbral
UMBRAL_PENDIENTE="${UMBRAL_PENDIENTE:-30}" # Anexo XI: pendiente > 30 %
VERSION="${VERSION:-v2-norma}"
INES="${INES:-$(psql -tAc "select distinct ine_code from edificacion order by ine_code")}"

echo "==> IUF (norma Anexo XI): franja ${FRANJA_BASE} m / ${FRANJA_PENDIENTE} m si pendiente>${UMBRAL_PENDIENTE}%, versión ${VERSION}"
for ine in $INES; do
  echo "--- municipio $ine ---"
  psql -v ON_ERROR_STOP=1 -v ine="$ine" -v fb="$FRANJA_BASE" -v fp="$FRANJA_PENDIENTE" \
       -v umbral="$UMBRAL_PENDIENTE" -v version="$VERSION" <<'SQL'
\timing on
insert into wui_edificacion (ref_catastral, ine_code, dist_forestal_m, area_forestal_en_franja_m2,
       frac_franja_ocupada, clase, cumple, advertencia_margen, franja_m, version_analisis)
with ring as (   -- franja por edificación según la pendiente (Anexo XI)
  select e.ref_catastral, e.ine_code, e.geom,
         (case when coalesce(e.pendiente_pct, 0) > :umbral then :fp else :fb end) franja
  from edificacion e
  where e.ine_code = :'ine'
),
r2 as (
  select ref_catastral, ine_code, geom, franja,
         st_difference(st_buffer(geom, franja), geom) anillo
  from ring
),
d as (   -- distancia al bosque más cercano (radio de búsqueda = la franja máxima * 3)
  select r.ref_catastral, min(st_distance(r.geom, f.geom)) dist
  from r2 r
  join terreno_forestal f on st_dwithin(r.geom, f.geom, :fp * 3)
  group by r.ref_catastral
),
occ as (  -- área forestal DENTRO del anillo (piezas subdivididas, sin solape -> suma directa)
  select r.ref_catastral, st_area(r.anillo) area_anillo,
         coalesce(sum(st_area(st_intersection(r.anillo, f.geom))), 0) area_forestal
  from r2 r
  left join terreno_forestal f on st_intersects(r.anillo, f.geom)
  group by r.ref_catastral, r.anillo
)
select r.ref_catastral, r.ine_code,
       round(d.dist::numeric, 2),
       round(occ.area_forestal::numeric, 2),
       round((occ.area_forestal / nullif(occ.area_anillo, 0))::numeric, 3),
       case
         when d.dist is null then 'cumple'
         when d.dist = 0 then 'critico'
         when d.dist < r.franja then 'incumple'
         else 'cumple'
       end,
       (d.dist is null or d.dist >= r.franja),                            -- cumple legalmente
       (d.dist is not null and d.dist >= r.franja and d.dist < r.franja * 1.5),  -- cautela técnica
       r.franja, :'version'
from r2 r
left join d using (ref_catastral)
left join occ using (ref_catastral)
on conflict (ref_catastral) do update set
  dist_forestal_m = excluded.dist_forestal_m,
  area_forestal_en_franja_m2 = excluded.area_forestal_en_franja_m2,
  frac_franja_ocupada = excluded.frac_franja_ocupada,
  clase = excluded.clase, cumple = excluded.cumple,
  advertencia_margen = excluded.advertencia_margen,
  franja_m = excluded.franja_m, version_analisis = excluded.version_analisis,
  calculado_en = now();
SQL
done
echo "==> wui_edificacion (norma): OK."
