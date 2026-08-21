#!/usr/bin/env bash
# Carga de espacios protegidos (Red Natura 2000 + ENP) -> espacio_protegido.
# Alimenta frac_espacio_protegido de comp_vulnerab (docs/04 §3). Llamado por
# seed.sh dentro del contenedor etl. Idempotente.
#
# Fuente: MITECO, Banco de Datos de la Naturaleza (límites OFICIALES, dic-2025):
#   * Red Natura 2000 (ZEC/ZEPA): n2000_spatial_es_pibal_proy_end25.shp
#   * ENP (parques, reservas, parajes…):          Enp2025_p.shp
# Ambos peninsulares, ya en EPSG:25830. Se DESCARTAN los ficheros de Canarias.
#
# Por qué MITECO y no la Infraestructura Verde del ICV: la capa IVM.RN2000/ENP
# del ICV es un subconjunto de planeamiento LOTUP (solo ~15.000 ha en Castellón,
# con el Desert de les Palmes fuera), no los límites de protección. MITECO son
# los límites reales (>100.000 ha en la provincia).
#
# Se recorta al CONTINENTE (mv_provincia_continental): la Reserva Natural de las
# Columbretes (marina/insular) y las porciones marinas de los ZEC costeros quedan
# fuera, como exige docs/04 §3.
set -euo pipefail

DATA=/data
DESC="https://www.miteco.gob.es/es/biodiversidad/servicios/banco-datos-naturaleza/informacion-disponible/rednatura_2000_desc.html"
RN2000_URL="https://www.miteco.gob.es/content/dam/miteco/es/biodiversidad/servicios/banco-datos-naturaleza/3-rn2000/n2000_2025_shp.zip"
ENP_URL="https://www.miteco.gob.es/content/dam/miteco/es/biodiversidad/servicios/banco-datos-naturaleza/enp/Enp2025_shp.zip"
PG="PG:host=$PGHOST port=$PGPORT dbname=$PGDATABASE user=$PGUSER password=$PGPASSWORD"
run_sql() { psql -v ON_ERROR_STOP=1 -q "$@"; }

mkdir -p "$DATA"

fetch_unzip() { # url, zipname, dir
  local url="$1" zip="$DATA/$2" dir="$DATA/$3"
  if [[ ! -f "$zip" ]]; then
    echo "==> Descargando $2 (MITECO)…"
    curl -fsSL -o "$zip" "$url"
  fi
  rm -rf "$dir"
  mkdir -p "$dir"
  unzip -oq "$zip" -d "$dir"
}

load_shp() { # shp_path, categoria, source_label
  local shp="$1" cat="$2" label="$3"
  if [[ ! -f "$shp" ]]; then
    echo "ERROR: no encuentro el shapefile de $cat: $shp" >&2
    exit 1
  fi
  echo "==> $cat: cargando $(basename "$shp") (25830 nativo, se fuerza por defensa)…"
  run_sql -c "drop table if exists stg_ep;"
  # -select "" : solo geometría, sin atributos. Además de que no los usamos, evita
  # que un campo como shape_area (~7e7 m2) desborde el numeric que GDAL infiere.
  ogr2ogr -f PostgreSQL "$PG" "$shp" \
    -nln stg_ep -overwrite -select "" \
    -t_srs EPSG:25830 -nlt PROMOTE_TO_MULTI \
    -lco GEOMETRY_NAME=geom --config PG_USE_COPY YES

  psql -v ON_ERROR_STOP=1 -v cat="$cat" -v label="$label" -v url="$DESC" <<'SQL'
\set QUIET on
begin;
-- Verificación empírica del SRID tras -t_srs (falla ruidosamente si no es 25830).
do $$
declare n int;
begin
  select count(*) into n from stg_ep where st_srid(geom) <> 25830;
  if n <> 0 then raise exception '% geoms de espacio protegido fuera de 25830', n; end if;
end $$;

-- Máscara continental subdividida + GiST (recorte eficiente del nacional).
drop table if exists _mask_ep;
create temp table _mask_ep as
  select st_subdivide(geom, 256) as g from mv_provincia_continental;
create index on _mask_ep using gist (g);
analyze _mask_ep;

insert into espacio_protegido (categoria, nombre, geom, source, source_url)
select :'cat', null,
       st_multi(st_collectionextract(st_intersection(s.g, m.g), 3)),
       :'label', :'url'
from (select st_makevalid(geom) g from stg_ep) s
join _mask_ep m on s.g && m.g
where st_intersects(s.g, m.g);

delete from espacio_protegido
where categoria = :'cat' and (geom is null or st_isempty(geom));
commit;
SQL
}

run_sql -c "truncate espacio_protegido restart identity;"

fetch_unzip "$RN2000_URL" rn2000.zip rn2000_shp
fetch_unzip "$ENP_URL" enp.zip enp_shp
RN2000_SHP=$(find "$DATA/rn2000_shp" -iname "*pibal*proy_end25.shp" | head -1)
ENP_SHP=$(find "$DATA/enp_shp" -iname "Enp*_p.shp" | head -1)

load_shp "$RN2000_SHP" RN2000 "MITECO Banco de Datos de la Naturaleza - Red Natura 2000 (dic-2025)"
load_shp "$ENP_SHP" ENP "MITECO Banco de Datos de la Naturaleza - ENP (dic-2025)"

# --- Aserciones (fallan ruidosamente) ----------------------------------------
echo "==> Verificando espacio_protegido…"
psql -v ON_ERROR_STOP=1 <<'SQL'
\set QUIET on
do $$
declare n_rn int; n_enp int; ha numeric; maxx numeric;
begin
  select count(*) into n_rn from espacio_protegido where categoria = 'RN2000';
  select count(*) into n_enp from espacio_protegido where categoria = 'ENP';
  if n_rn = 0 then raise exception 'sin Red Natura 2000 cargada'; end if;
  if n_enp = 0 then raise exception 'sin ENP cargados'; end if;

  -- Nada mar adentro: si las Columbretes (X~815000) o el mar hubieran entrado, el
  -- extent se saldría del continente (maxX ~802748). O(1).
  select st_xmax(st_extent(geom)::geometry) into maxx from espacio_protegido;
  if maxx > 805000 then
    raise exception 'espacio_protegido se sale del continente (maxX=%): ¿marino dentro?', maxx;
  end if;

  -- Con los límites reales de MITECO, Castellón tiene MUCHO suelo protegido
  -- (Espadán, Penyagolosa, Irta, Prat de Cabanes, Tinença…): banda amplia pero
  -- muy por encima del subconjunto del ICV (que daba ~15.000 ha).
  select round(st_area(st_union(geom)) / 10000) into ha from espacio_protegido;
  if ha < 80000 or ha > 400000 then
    raise exception 'ha protegidas (dedup) = % fuera de [80000, 400000]', ha;
  end if;
  raise notice 'suelo protegido dedup: % ha (RN2000 % feat, ENP % feat)', ha, n_rn, n_enp;
end $$;
SQL
echo "==> espacio_protegido: OK."

run_sql -c "drop table if exists stg_ep;"
