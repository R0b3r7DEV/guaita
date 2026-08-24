#!/usr/bin/env bash
# Carga de perímetros de incendio de la GVA/ICV (1993-2024) -> perimetro_incendio.
# Fuente: ArcGIS MapServer del ICV (una capa por año; los ids NO son secuenciales,
# se resuelven del propio servicio). Se cargan 2005-2024 (rango del backtest) y solo
# provincia de Castellón. La geometría se pide ya en EPSG:25830 (outSR) y se fuerza
# el SRS (el GeoJSON declara 4326 por defecto aunque las coords sean 25830).
set -euo pipefail

: "${PGHOST:?falta PGHOST}"
: "${PGDATABASE:?falta PGDATABASE}"
: "${PGUSER:?falta PGUSER}"
: "${PGPASSWORD:?falta PGPASSWORD}"
PGPORT="${PGPORT:-5432}"

B="http://carto.icv.gva.es/arcgis/rest/services/tm_medio_ambiente/prevencion_de_incendios/MapServer"
DATA=/data/perim_gva
PG="PG:host=$PGHOST port=$PGPORT dbname=$PGDATABASE user=$PGUSER password=$PGPASSWORD"
run_sql() { psql -v ON_ERROR_STOP=1 -q "$@"; }
# where prov_nom LIKE '%Castell%' y campos, ya URL-encoded.
FLDS="NumPIF_Min,anyo,f_detec,nom_mun,prov_nom,sup_f"
WHERE="prov_nom+LIKE+%27%25Castell%25%27"

mkdir -p "$DATA"
echo "==> Descargando el catálogo de capas del MapServer…"
curl -fsSL "$B?f=json" -o "$DATA/service.json"

run_sql -c "drop table if exists stg_perim;"
first=1
for y in $(seq 2005 2024); do
  id=$(grep -oE "\{\"id\":[0-9]+,\"name\":\"Incendios $y[^}]*" "$DATA/service.json" \
       | grep -oE '"id":[0-9]+' | grep -oE '[0-9]+' | head -1)
  if [[ -z "$id" ]]; then
    echo "   $y: sin capa, se omite"
    continue
  fi
  url="$B/$id/query?where=$WHERE&outFields=$FLDS&outSR=25830&returnGeometry=true&f=geojson&resultRecordCount=5000"
  curl -fsSL "$url" -o "$DATA/$y.geojson"
  n=$(grep -o '"type":"Feature"' "$DATA/$y.geojson" | wc -l)
  echo "   $y (capa $id): $n perímetros"
  if [[ "$n" -eq 0 ]]; then
    continue
  fi
  if [[ "$first" -eq 1 ]]; then
    ogr2ogr -f PostgreSQL "$PG" "$DATA/$y.geojson" -nln stg_perim -overwrite \
      -nlt PROMOTE_TO_MULTI -a_srs EPSG:25830 -lco GEOMETRY_NAME=geom --config PG_USE_COPY YES
    first=0
  else
    ogr2ogr -f PostgreSQL "$PG" "$DATA/$y.geojson" -nln stg_perim -append \
      -nlt PROMOTE_TO_MULTI -a_srs EPSG:25830
  fi
done

echo "==> Transformando a perimetro_incendio…"
psql -v ON_ERROR_STOP=1 <<'SQL'
\set QUIET on
begin;
truncate perimetro_incendio;
insert into perimetro_incendio (numpif, anyo, fecha, nom_mun, prov, sup_f_ha, geom, source)
select numpif_min, anyo,
       to_date(nullif(f_detec, ''), 'DD/MM/YYYY'),
       nom_mun, prov_nom, sup_f,
       st_multi(st_collectionextract(st_makevalid(geom), 3)),
       'ICV/GVA perímetros de incendio 1993-2024 (CC-BY)'
from stg_perim
where geom is not null and not st_isempty(geom);

-- ================= ASERCIONES (fallan ruidosamente) =====================
do $$
declare n int; nmun int; ha numeric; area numeric;
begin
  select count(*) into n from perimetro_incendio where st_srid(geom) <> 25830;
  if n <> 0 then raise exception '% perímetros fuera de 25830', n; end if;

  -- Bejís 2022 (numpif 2022120052): debe cubrir ~12 municipios y ~16.800 ha.
  select count(distinct m.ine_code) into nmun
  from perimetro_incendio p join municipio m
    on p.geom && m.geom and st_intersects(p.geom, m.geom)
  where p.numpif = '2022120052';
  select sup_f_ha, round(st_area(geom)/10000) into ha, area
  from perimetro_incendio where numpif = '2022120052';
  raise notice 'Bejís: % municipios tocados, sup_f atributo=% ha, area geom=% ha', nmun, ha, area;
  if nmun < 8 or nmun > 16 then
    raise exception 'Bejís cubre % municipios (esperaba ~12): problema de geometría', nmun;
  end if;
  if area < 12000 or area > 25000 then
    raise exception 'Bejís area geom = % ha (esperaba ~16.800): problema de emparejamiento', area;
  end if;
end $$;
commit;
SQL
echo "==> perimetro_incendio: OK."
run_sql -c "drop table if exists stg_perim;"
