#!/usr/bin/env bash
# Carga del modelo de combustible del PATFOR (Fase 1). Llamado por seed.sh.
# Idempotente. Capa `Regulacion.Incendios.Combustible` del WFS del PATFOR,
# esquema Anderson/Rothermel (campo mod_comb). Tabla PROPIA, sin cruzar contra
# terreno_forestal (ese overlay es Fase 3).
#
# Se cargan los modelos 2..8 (vegetación). Se EXCLUYE mod_comb='0' (no
# combustible): son ~1,07 M ha de fondo no forestal, polígonos gigantes que no
# aportan modelo y dispararían el coste. codigo_origen = valor crudo, sin remapear.
#
# Recorte: continental + 5 km, igual que SF.Forestal (docs/02). Clip con máscaras
# subdivididas e indexadas (GiST) para que termine en tiempo razonable.
set -euo pipefail

DATA=/data
REPORTS=/etl/reports
INVENTARIO="$REPORTS/patfor-combustible-inventario.md"
MC_URL="https://terramapas.icv.gva.es/0506_PATFOR?request=GetFeature&service=WFS&version=2.0.0&typename=Regulacion.Incendios.Combustible&outputformat=gpkg"
GPKG="$DATA/patfor_combustible.gpkg"
BUFFER_M=5000
PG="PG:host=$PGHOST port=$PGPORT dbname=$PGDATABASE user=$PGUSER password=$PGPASSWORD"
run_sql() { psql -v ON_ERROR_STOP=1 -q "$@"; }

mkdir -p "$DATA" "$REPORTS"

# --- 1. Descarga (GeoPackage, cache) ------------------------------------------
if [[ ! -f "$GPKG" ]]; then
  echo "==> Descargando modelo de combustible del PATFOR (ICV, gpkg)…"
  curl -fsSL -o "$GPKG" "$MC_URL"
fi

# --- 2. SRID declarado + carga a staging forzando 25830 -----------------------
SRID_DECL=$(python3 -c "import sqlite3; print(sqlite3.connect('$GPKG').execute('select srs_id from gpkg_geometry_columns').fetchone()[0])")
echo "==> SRID declarado por el GeoPackage: EPSG:$SRID_DECL (se fuerza la salida a 25830)"

run_sql -c "drop table if exists stg_mc;"
ogr2ogr -f PostgreSQL "$PG" "$GPKG" "Regulacion.Incendios.Combustible" \
  -nln stg_mc -overwrite \
  -t_srs EPSG:25830 -nlt MULTIPOLYGON \
  -lco GEOMETRY_NAME=geom --config PG_USE_COPY YES

# --- 3a. Máscara subdividida + forestal de origen (modelos 2..8), indexados ---
echo "==> Preparando máscaras subdivididas + índices GiST…"
run_sql <<SQL
\set QUIET on
drop table if exists _mc_mask, _mc_sub;
create table _mc_mask as
  select st_subdivide(st_buffer(geom, ${BUFFER_M}), 256) as g from mv_provincia_continental;
create index on _mc_mask using gist (g);
-- solo modelos de combustible reales (excluye '0' = no combustible)
create table _mc_sub as
  select mod_comb as codigo, st_subdivide(st_makevalid(geom), 256) as g
  from stg_mc where mod_comb <> '0';
create index on _mc_sub using gist (g);
analyze _mc_mask; analyze _mc_sub;
SQL

# --- 3b. Clip + carga idempotente (transacción con aserciones) ----------------
echo "==> Recortando (join espacial con &&) + carga…"
psql -v ON_ERROR_STOP=1 <<SQL
\set QUIET on
begin;
truncate modelo_combustible_patfor restart identity;

insert into modelo_combustible_patfor (codigo_origen, geom, source)
select codigo, st_multi(g), 'PATFOR:Regulacion.Incendios.Combustible (ICV/GVA)'
from (
  select f.codigo, st_collectionextract(st_intersection(f.g, m.g), 3) as g
  from _mc_sub f join _mc_mask m on f.g && m.g
  where st_intersects(f.g, m.g)
) x
where g is not null and not st_isempty(g);

-- ===================== ASERCIONES (fallan ruidosamente) =====================
do \$\$
declare n int; ha numeric;
begin
  select count(*) into n from modelo_combustible_patfor;
  if n = 0 then raise exception 'modelo_combustible_patfor vacío tras la carga'; end if;

  select count(*) into n from modelo_combustible_patfor where st_srid(geom) <> 25830;
  if n <> 0 then raise exception '% geometrías fuera de SRID 25830', n; end if;

  select count(*) into n from modelo_combustible_patfor where not st_isvalid(geom);
  if n <> 0 then raise exception '% geometrías inválidas (ST_IsValid)', n; end if;

  -- codigo_origen dentro del set esperado (Anderson 2..8).
  select count(*) into n from modelo_combustible_patfor
   where codigo_origen not in ('2','3','4','5','6','7','8');
  if n <> 0 then raise exception '% filas con codigo_origen inesperado (no 2..8)', n; end if;

  -- Nada mar adentro: extent dentro del buffer (O(1)).
  if not st_within(
       st_envelope((select st_extent(geom)::geometry from modelo_combustible_patfor)),
       st_expand(st_envelope((select st_extent(g)::geometry from _mc_mask)), 1)
     ) then
    raise exception 'modelo_combustible_patfor se sale del continental+${BUFFER_M}m (¿clip mal?)';
  end if;

  -- Plausibilidad de superficie (comparable al monte, ~cientos de miles de ha).
  select round(sum(st_area(geom))/10000) into ha from modelo_combustible_patfor;
  if ha < 150000 or ha > 800000 then
    raise exception 'ha de modelo de combustible = % fuera de [150000, 800000]', ha;
  end if;
end \$\$;
commit;
SQL
echo "==> modelo_combustible_patfor: OK (cargado, recortado y verificado)."

# --- 4. Inventario ------------------------------------------------------------
echo "==> Generando $INVENTARIO…"
{
  echo "# Inventario del modelo de combustible PATFOR"
  echo
  echo "> Generado por \`make seed\` (etl/load_modelo_combustible.sh). No editar a mano."
  echo
  echo "## Esquema"
  echo
  echo "Capa \`Regulacion.Incendios.Combustible\` del WFS del PATFOR, campo"
  echo "\`mod_comb\` (numérico). Es la clasificación **Anderson/Rothermel** (13"
  echo "modelos), de la que aquí aparecen solo **7 de vegetación (2..8)** más un"
  echo "**0 = no combustible** (fondo no forestal). Faltan el 1 (pasto corto) y"
  echo "**9..13** (hojarasca/restos de corta), que rara vez salen en cartografía"
  echo "estática. **No hay leyenda machine-readable** en el GPKG; la"
  echo "correspondencia con Anderson es la numeración estándar (hipótesis de"
  echo "trabajo, a confirmar contra metadato oficial en Fase 3)."
  echo
  echo "El \`0\` (no combustible) se **excluye** de la tabla (son ~1,07 M ha de"
  echo "fondo no forestal). Distribución cruda en origen (toda la CV):"
  echo
  echo '```'
} > "$INVENTARIO"
run_sql >> "$INVENTARIO" <<'SQL'
\pset border 2
select mod_comb as codigo_crudo,
       count(*) as n,
       round(sum(st_area(geom))/10000) as ha_cv
from stg_mc group by 1 order by 3 desc;
SQL
{
  echo '```'
  echo
  echo "## (a) Modelos cargados en \`modelo_combustible_patfor\` (recorte Castellón+5km)"
  echo
  echo '```'
} >> "$INVENTARIO"
run_sql >> "$INVENTARIO" <<'SQL'
\pset border 2
select codigo_origen,
       count(*) as n_poligonos,
       round(sum(st_area(geom))/10000) as ha
from modelo_combustible_patfor group by 1 order by 3 desc;
SQL
{
  echo '```'
  echo
  echo "## (b) Atributos de la capa de origen (staging \`stg_mc\`)"
  echo
  echo '```'
} >> "$INVENTARIO"
run_sql >> "$INVENTARIO" <<'SQL'
\pset border 2
\echo Columnas (nombre | tipo):
select column_name, data_type from information_schema.columns
where table_name = 'stg_mc' order by ordinal_position;
\echo
\echo Tres filas de ejemplo (atributos, sin geometría):
select fid, mod_comb, shape_length, shape_area from stg_mc limit 3;
SQL
{
  echo '```'
  echo
  echo "## (c) Cobertura: ¿qué % del terreno forestal tiene modelo de combustible?"
  echo
  echo "Si la cobertura es parcial, el monte sin modelo necesita un plan B en"
  echo "Fase 3. (Métrica de solape; el overlay real que asigna modelo a cada"
  echo "polígono forestal es Fase 3.)"
  echo
  echo '```'
} >> "$INVENTARIO"
run_sql >> "$INVENTARIO" <<'SQL'
\pset border 2
with cov as (
  select sum(st_area(st_intersection(t.geom, m.geom))) as a
  from terreno_forestal t
  join modelo_combustible_patfor m on t.geom && m.geom
  where st_intersects(t.geom, m.geom)
),
tot as (select sum(st_area(geom)) as a from terreno_forestal)
select round((tot.a/10000)::numeric)                 as ha_terreno_forestal,
       round((cov.a/10000)::numeric)                 as ha_con_modelo,
       round((100.0 * cov.a / tot.a)::numeric, 1)    as pct_cobertura
from cov, tot;
SQL
echo '```' >> "$INVENTARIO"
echo "==> inventario escrito en $INVENTARIO."

# --- 5. Limpieza --------------------------------------------------------------
run_sql -c "drop table if exists _mc_mask, _mc_sub, stg_mc;"
