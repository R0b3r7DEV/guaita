#!/usr/bin/env bash
# Análisis de franja perimetral IUF (docs/05) -> wui_edificacion. Por municipio, en PostGIS.
#
# CLAVE de rendimiento (docs/05 + revisión): NO se hace ST_Union de la capa forestal (el ST_Union de
# un término grande la haría inviable). terreno_forestal YA viene SUBDIVIDIDA de Fase 1 (ST_Subdivide,
# piezas <=256 vértices, sin solape), así que se cruza directamente: distancia al bosque por
# ST_DWithin acotado (índice GiST) y ocupación del anillo por suma de intersecciones (las piezas no
# solapan -> sumar no doble-cuenta). franja_m parametrizable (por defecto 30) y registrada.
set -euo pipefail

: "${PGHOST:?falta PGHOST}"; : "${PGDATABASE:?falta PGDATABASE}"
: "${PGUSER:?falta PGUSER}"; : "${PGPASSWORD:?falta PGPASSWORD}"

FRANJA="${FRANJA:-30}"
VERSION="${VERSION:-v1}"
# Municipios a analizar (ine_code separados por espacio); por defecto los que tengan edificaciones.
INES="${INES:-$(psql -tAc "select distinct ine_code from edificacion order by ine_code")}"

echo "==> Análisis IUF: franja ${FRANJA} m, versión ${VERSION}. Municipios: ${INES}"
for ine in $INES; do
  echo "--- municipio $ine ---"
  psql -v ON_ERROR_STOP=1 -v ine="$ine" -v franja="$FRANJA" -v version="$VERSION" <<'SQL'
\timing on
insert into wui_edificacion (ref_catastral, ine_code, dist_forestal_m, area_forestal_en_franja_m2,
       frac_franja_ocupada, clase, cumple, franja_m, version_analisis)
with ring as (
  select e.ref_catastral, e.ine_code, e.geom,
         st_difference(st_buffer(e.geom, :franja), e.geom) anillo
  from edificacion e
  where e.ine_code = :'ine'
),
d as (   -- distancia al bosque más cercano (búsqueda acotada por ST_DWithin + GiST)
  select r.ref_catastral, min(st_distance(r.geom, f.geom)) dist
  from ring r
  join terreno_forestal f on st_dwithin(r.geom, f.geom, :franja * 3)
  group by r.ref_catastral
),
occ as (  -- área forestal DENTRO del anillo (piezas subdivididas, sin solape -> suma directa)
  select r.ref_catastral, st_area(r.anillo) area_anillo,
         coalesce(sum(st_area(st_intersection(r.anillo, f.geom))), 0) area_forestal
  from ring r
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
         when d.dist < :franja then 'incumple'
         when d.dist < :franja * 1.5 then 'ajustado'
         else 'cumple'
       end,
       (d.dist is null or d.dist >= :franja),
       :franja, :'version'
from ring r
left join d using (ref_catastral)
left join occ using (ref_catastral)
on conflict (ref_catastral) do update set
  dist_forestal_m = excluded.dist_forestal_m,
  area_forestal_en_franja_m2 = excluded.area_forestal_en_franja_m2,
  frac_franja_ocupada = excluded.frac_franja_ocupada,
  clase = excluded.clase, cumple = excluded.cumple,
  franja_m = excluded.franja_m, version_analisis = excluded.version_analisis,
  calculado_en = now();
SQL
done
echo "==> wui_edificacion: OK."
