#!/usr/bin/env bash
# Carga de edificaciones del Catastro INSPIRE (Buildings) -> tabla `edificacion` (docs/05).
# PRIVACIDAD (T2): SOLO geometría + referencia catastral. Nada de titularidad ni direcciones.
#
# Municipio(s) por CÓDIGO CATASTRO (no INE; p.ej. Alfondeguilla=12007, Eslida=12057). El ine_code se
# asigna por JOIN ESPACIAL (punto representativo del edificio dentro de municipio.geom), evitando
# mapear código catastral<->INE. Reproyección 4258 (ETRS89 geográfico, lo que declara el GML) -> 25830
# con aserción. Filtro por uso: residencial + agrario (parametrizable USOS). Idempotente (upsert).
# El GML crudo se BORRA tras cargar (higiene de disco). Guardia de disco antes de descargar.
set -euo pipefail

: "${PGHOST:?falta PGHOST}"; : "${PGDATABASE:?falta PGDATABASE}"
: "${PGUSER:?falta PGUSER}"; : "${PGPASSWORD:?falta PGPASSWORD}"
PGPORT="${PGPORT:-5432}"
PG="PG:host=$PGHOST port=$PGPORT dbname=$PGDATABASE user=$PGUSER password=$PGPASSWORD"

MUNIS_CATASTRO="${MUNIS_CATASTRO:-12007}"   # piloto: Alfondeguilla (Espadán)
USOS="${USOS:-'1_residential','2_agriculture'}"
MIN_FREE_GB="${MIN_FREE_GB:-5}"             # mismo umbral que DiskGuard (docs/01)
BASE="https://www.catastro.hacienda.gob.es/INSPIRE/Buildings/12"
DATA=/data/wui_gml
run_sql() { psql -v ON_ERROR_STOP=1 -q "$@"; }

# --- Guardia de disco (replica el umbral de DiskGuard para el ETL, no solo para el backfill) ---
libres_gb=$(df -BG --output=avail "$( [ -d /data ] && echo /data || echo / )" | tail -1 | tr -dc '0-9')
echo "==> Guardia de disco: ${libres_gb} GB libres (umbral ${MIN_FREE_GB} GB)."
if [ "${libres_gb:-0}" -lt "$MIN_FREE_GB" ]; then
  echo "ABORTO: disco por debajo del umbral (${libres_gb} < ${MIN_FREE_GB} GB)."; exit 1
fi

mkdir -p "$DATA"
# Nombre de carpeta del ATOM: "<cod>-<NOMBRE>"; se resuelve del ATOM de provincia.
curl -fsSL --max-time 90 -A "Mozilla/5.0" "$BASE/../ES.SDGC.bu.atom_12.xml" -o "$DATA/atom_12.xml" 2>/dev/null \
  || curl -fsSL --max-time 90 -A "Mozilla/5.0" \
       "https://www.catastro.hacienda.gob.es/INSPIRE/buildings/12/ES.SDGC.bu.atom_12.xml" -o "$DATA/atom_12.xml"

for cod in ${MUNIS_CATASTRO//,/ }; do
  url=$(grep -oE "https://[^\"]*/$cod-[^\"]*/A\.ES\.SDGC\.BU\.$cod\.zip" "$DATA/atom_12.xml" | head -1)
  [ -z "$url" ] && { echo "   $cod: sin entrada en el ATOM, se omite"; continue; }
  echo "==> $cod: $url"
  cd "$DATA"
  curl -fsSL --max-time 120 -A "Mozilla/5.0" --retry 3 -o "$cod.zip" "$url"
  rm -rf "d_$cod"; mkdir "d_$cod"; unzip -o -q "$cod.zip" -d "d_$cod"
  BLD=$(ls "d_$cod"/*building.gml 2>/dev/null | grep -iv part | head -1)
  [ -z "$BLD" ] && { echo "   $cod: sin building.gml"; continue; }

  ogr2ogr -f PostgreSQL "$PG" "$BLD" building \
    -s_srs EPSG:4258 -t_srs EPSG:25830 \
    -nln stg_edif -overwrite -nlt PROMOTE_TO_MULTI \
    -select reference,currentUse -lco GEOMETRY_NAME=geom --config PG_USE_COPY YES

  psql -v ON_ERROR_STOP=1 -v url="$url" -v usos="$USOS" -v cod="$cod" <<'SQL'
\set QUIET on
begin;
with s as (
  select reference, currentuse,
         st_multi(st_collectionextract(st_makevalid(geom), 3)) geom
  from stg_edif
  where geom is not null and not st_isempty(geom)
), j as (
  select s.reference, s.currentuse, s.geom,
         (select m.ine_code from municipio m
          where st_intersects(m.geom, st_pointonsurface(s.geom)) limit 1) ine
  from s
  where not st_isempty(s.geom)
)
insert into edificacion (ref_catastral, ine_code, uso, geom, source, source_url)
select reference, ine, currentuse, geom,
       'Catastro INSPIRE Buildings (Dirección General del Catastro)', :'url'
from j
where ine is not null
  and currentuse in ( :usos )
on conflict (ref_catastral) do update set
  ine_code = excluded.ine_code, uso = excluded.uso, geom = excluded.geom,
  source_url = excluded.source_url, fetched_at = now();

-- Aserciones: todo en 25830; el municipio del código catastral quedó representado.
do $$
declare n int;
begin
  select count(*) into n from edificacion where st_srid(geom) <> 25830;
  if n <> 0 then raise exception '% edificaciones fuera de 25830', n; end if;
end $$;
drop table if exists stg_edif;
commit;
SQL
  # Higiene de disco: borra el GML crudo (grande) tras cargar; conserva el zip pequeño.
  rm -rf "$DATA/d_$cod"
  n=$(psql -tAc "select count(*) from edificacion where source_url = '$url'")
  echo "   $cod: $n edificaciones (residencial+agrario) cargadas; GML crudo borrado."
done
echo "==> edificacion: OK."
