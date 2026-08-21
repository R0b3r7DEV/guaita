#!/usr/bin/env bash
# Carga de espacios protegidos (Red Natura 2000 + ENP) -> espacio_protegido.
# Alimenta frac_espacio_protegido de comp_vulnerab (docs/04 §3). Llamado por
# seed.sh dentro del contenedor etl. Idempotente.
#
# Fuente: Infraestructura Verde del ICV/GVA (mismo geoportal WFS que el PATFOR),
# servicio 0701, capas IVM.RN2000 (ZEC/ZEPA) e IVM.ENP (parques, reservas…).
# Se recorta al CONTINENTE (mv_provincia_continental): la Reserva Natural de las
# Columbretes (marina, sin combustible ni interfaz) queda fuera, docs/04 §3.
#
# Rendimiento: clip con máscara continental SUBDIVIDIDA e indexada (GiST) y join
# espacial con `&&`, como en load_terreno_forestal.sh.
set -euo pipefail

DATA=/data
BASE="https://terramapas.icv.gva.es/0701_InfraestructuraVerde?request=GetFeature&service=WFS&version=2.0.0&outputformat=gpkg&typename="
PG="PG:host=$PGHOST port=$PGPORT dbname=$PGDATABASE user=$PGUSER password=$PGPASSWORD"
run_sql() { psql -v ON_ERROR_STOP=1 -q "$@"; }

mkdir -p "$DATA"
run_sql -c "truncate espacio_protegido restart identity;"

load_layer() { # $1 typename, $2 categoria
  local typ="$1" cat="$2"
  local gpkg="$DATA/ep_${cat}.gpkg"
  if [[ ! -f "$gpkg" ]]; then
    echo "==> Descargando $typ (ICV, gpkg)…"
    curl -fsSL -o "$gpkg" "${BASE}${typ}"
  fi
  local srid
  srid=$(python3 -c "import sqlite3; print(sqlite3.connect('$gpkg').execute('select srs_id from gpkg_geometry_columns').fetchone()[0])")
  echo "==> $typ: SRID declarado EPSG:$srid (se fuerza la salida a 25830)"

  run_sql -c "drop table if exists stg_ep;"
  ogr2ogr -f PostgreSQL "$PG" "$gpkg" "$typ" \
    -nln stg_ep -overwrite \
    -t_srs EPSG:25830 -nlt PROMOTE_TO_MULTI \
    -lco GEOMETRY_NAME=geom --config PG_USE_COPY YES

  psql -v ON_ERROR_STOP=1 -v cat="$cat" -v typ="$typ" <<'SQL'
\set QUIET on
begin;
-- Verificación empírica del SRID tras -t_srs (falla ruidosamente si no es 25830).
do $$
declare n int;
begin
  select count(*) into n from stg_ep where st_srid(geom) <> 25830;
  if n <> 0 then raise exception '% geoms de espacio protegido fuera de 25830', n; end if;
end $$;

-- Máscara continental subdividida + GiST (recorte eficiente).
drop table if exists _mask_ep;
create temp table _mask_ep as
  select st_subdivide(geom, 256) as g from mv_provincia_continental;
create index on _mask_ep using gist (g);
analyze _mask_ep;

insert into espacio_protegido (categoria, nombre, geom, source, source_url)
select :'cat', null,
       st_multi(st_collectionextract(st_intersection(s.g, m.g), 3)),
       'ICV/GVA Infraestructura Verde: ' || :'typ',
       'https://terramapas.icv.gva.es/0701_InfraestructuraVerde'
from (select st_makevalid(geom) g from stg_ep) s
join _mask_ep m on s.g && m.g
where st_intersects(s.g, m.g);

delete from espacio_protegido
where categoria = :'cat' and (geom is null or st_isempty(geom));
commit;
SQL
}

load_layer "IVM.RN2000" "RN2000"
load_layer "IVM.ENP" "ENP"

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

  -- Nada mar adentro: si las Columbretes (X~815000) hubieran entrado, el extent
  -- se saldría del continente (maxX ~802748). O(1).
  select st_xmax(st_extent(geom)::geometry) into maxx from espacio_protegido;
  if maxx > 805000 then
    raise exception 'espacio_protegido se sale del continente (maxX=%): ¿Columbretes dentro?', maxx;
  end if;

  -- Superficie protegida total plausible (Castellón tiene decenas de miles de ha
  -- entre parques y Red Natura). Amplio a propósito.
  select round(sum(st_area(geom)) / 10000) into ha from espacio_protegido;
  if ha < 20000 or ha > 500000 then
    raise exception 'ha protegidas = % fuera de [20000, 500000]', ha;
  end if;
end $$;
SQL
echo "==> espacio_protegido: OK."

run_sql -c "drop table if exists stg_ep;"
