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

MUNIS_CATASTRO="${MUNIS_CATASTRO:-all}"     # 'all' = los 135 del ATOM; o lista de códigos catastro
USOS="${USOS:-'1_residential','2_agriculture'}"
MIN_FREE_GB="${MIN_FREE_GB:-5}"             # mismo umbral que DiskGuard (docs/01)
FORCE="${FORCE:-0}"                         # 1 = recargar aunque ya esté (cartografía nueva)
BASE="https://www.catastro.hacienda.gob.es/INSPIRE/Buildings/12"
ATOM="https://www.catastro.hacienda.gob.es/INSPIRE/buildings/12/ES.SDGC.bu.atom_12.xml"
DATA=/data/wui_gml
run_sql() { psql -v ON_ERROR_STOP=1 -q "$@"; }

# --- Guardia de disco (replica el umbral de DiskGuard para el ETL, no solo para el backfill) ---
libres_gb=$(df -BG --output=avail "$( [ -d /data ] && echo /data || echo / )" | tail -1 | tr -dc '0-9')
echo "==> Guardia de disco: ${libres_gb} GB libres (umbral ${MIN_FREE_GB} GB)."
if [ "${libres_gb:-0}" -lt "$MIN_FREE_GB" ]; then
  echo "ABORTO: disco por debajo del umbral (${libres_gb} < ${MIN_FREE_GB} GB)."; exit 1
fi

mkdir -p "$DATA"
# El ATOM de provincia da la carpeta "<cod>-<NOMBRE>" y la URL del zip de cada municipio.
curl -fsSL --max-time 90 -A "Mozilla/5.0" --retry 3 "$ATOM" -o "$DATA/atom_12.xml"

if [ "$MUNIS_CATASTRO" = "all" ]; then
  CODES=$(grep -oE 'A\.ES\.SDGC\.BU\.[0-9]+\.zip' "$DATA/atom_12.xml" | grep -oE '[0-9]+' | sort -u)
else
  CODES="${MUNIS_CATASTRO//,/ }"
fi
echo "==> municipios a procesar: $(echo "$CODES" | wc -w)"

for cod in $CODES; do
  url=$(grep -oE "https://[^\"]*/$cod-[^\"]*/A\.ES\.SDGC\.BU\.$cod\.zip" "$DATA/atom_12.xml" | head -1)
  [ -z "$url" ] && { echo "   $cod: sin entrada en el ATOM, se omite"; continue; }
  url="${url// /%20}"   # muchos términos llevan espacios en la carpeta (curl los rechaza sin %20)
  # Reanudable: si ya se cargó este municipio (por su URL de origen), se salta (FORCE=1 recarga).
  if [ "$FORCE" != "1" ] && [ "$(psql -tAc "select 1 from edificacion where source_url = '$url' limit 1")" = "1" ]; then
    echo "   $cod: ya cargado, se salta"; continue
  fi
  echo "==> $cod: $url"
  cd "$DATA"
  if ! curl -fsSL --max-time 120 -A "Mozilla/5.0" --retry 3 -o "$cod.zip" "$url"; then
    echo "   $cod: descarga falló, se omite (reanudable)"; continue
  fi
  rm -rf "d_$cod"; mkdir "d_$cod"; unzip -o -q "$cod.zip" -d "d_$cod"
  BLD=$(ls "d_$cod"/*building.gml 2>/dev/null | grep -iv part | head -1)
  [ -z "$BLD" ] && { echo "   $cod: sin building.gml"; rm -rf "d_$cod" "$cod.zip"; continue; }

  # El GML declara su propio SRS (Catastro INSPIRE = ETRS89/UTM 30N = 25830); NO forzar -s_srs
  # (interpretaría los metros como grados). Solo reproyectar a 25830; la aserción confirma el SRID.
  ogr2ogr -f PostgreSQL "$PG" "$BLD" building \
    -t_srs EPSG:25830 \
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

echo "==> Aserciones provinciales…"
psql -v ON_ERROR_STOP=1 <<'SQL'
\set QUIET on
do $$
declare fuera int; inval int; nmun int; tot int;
begin
  select count(*) into fuera from edificacion where st_srid(geom) <> 25830;
  if fuera <> 0 then raise exception '% edificaciones fuera de 25830', fuera; end if;
  select count(*) into inval from edificacion where not st_isvalid(geom);
  if inval <> 0 then raise exception '% geometrías inválidas', inval; end if;
  select count(distinct ine_code), count(*) into nmun, tot from edificacion;
  raise notice 'edificacion: % filas en % municipios (SRID 25830, todas válidas)', tot, nmun;
  if nmun < 130 then raise exception 'solo % municipios con edificaciones (esperaba ~135)', nmun; end if;
end $$;
SQL
echo "==> edificacion: OK."
