#!/usr/bin/env bash
# Carga de terreno forestal PATFOR (Fase 1, capa de EXTENSIÓN). Llamado por
# seed.sh dentro del contenedor etl. Idempotente.
#
# La capa SF.Forestal del PATFOR NO trae modelos de combustible: solo extensión
# forestal (atributos forestal/compatible/prov). modelo_combustible y modelo_norm
# quedan NULL. El modelo de combustible existe en OTRA capa del WFS
# (Regulacion.Incendios.Combustible, clas. Rothermel) -> Fase 3, ver docs/04.
#
# Recorte: contra mv_provincia_continental + buffer de 5 km. NO contra la
# geometría administrativa (las Columbretes llevarían el clip ~25 km mar
# adentro) ni contra el límite estricto. Motivo forestal: el fuego cruza
# límites administrativos (Bejís 2022 se propagó de Castellón a Valencia).
# LIMITACIÓN CONOCIDA: el PATFOR solo cubre la Comunitat Valenciana, así que el
# buffer capta la franja VALENCIANA fronteriza pero NO la ARAGONESA (Teruel).
# No se busca aquí la capa equivalente de Aragón. Documentado en docs/02.
#
# Fuente GeoPackage (UTF-8, SRID 25830 nativo): sin los problemas de shapefile
# (ISO-8859-1, truncado de campos a 10 caracteres). No aplican.
#
# Rendimiento: el clip y las agregaciones se hacen con máscaras SUBDIVIDIDAS e
# indexadas (GiST) y join espacial con `&&`. Intersectar contra una geometría
# única y enorme sin prefiltro hacía que el seed no terminara en 20 min.
set -euo pipefail

DATA=/data
REPORTS=/etl/reports
INVENTARIO="$REPORTS/patfor-inventario.md"
PATFOR_URL="https://terramapas.icv.gva.es/0506_PATFOR?request=GetFeature&service=WFS&version=2.0.0&typename=SF.Forestal&outputformat=gpkg"
GPKG="$DATA/patfor.gpkg"
BUFFER_M=5000
PG="PG:host=$PGHOST port=$PGPORT dbname=$PGDATABASE user=$PGUSER password=$PGPASSWORD"
run_sql() { psql -v ON_ERROR_STOP=1 -q "$@"; }

mkdir -p "$DATA" "$REPORTS"

# --- 1. Descarga del PATFOR (GeoPackage, cache) -------------------------------
if [[ ! -f "$GPKG" ]]; then
  echo "==> Descargando terreno forestal del PATFOR (ICV, gpkg)…"
  curl -fsSL -o "$GPKG" "$PATFOR_URL"
fi

# --- 2. SRID declarado (empírico) + carga a staging forzando 25830 ------------
SRID_DECL=$(python3 -c "import sqlite3; print(sqlite3.connect('$GPKG').execute('select srs_id from gpkg_geometry_columns').fetchone()[0])")
echo "==> SRID declarado por el GeoPackage: EPSG:$SRID_DECL (se fuerza la salida a 25830)"

run_sql -c "drop table if exists stg_patfor;"
ogr2ogr -f PostgreSQL "$PG" "$GPKG" "SF.Forestal" \
  -nln stg_patfor -overwrite \
  -t_srs EPSG:25830 -nlt MULTIPOLYGON \
  -lco GEOMETRY_NAME=geom --config PG_USE_COPY YES

# --- 3a. Máscaras subdivididas e indexadas (una sola vez) ---------------------
echo "==> Preparando máscaras subdivididas + índices GiST…"
run_sql <<SQL
\set QUIET on
drop table if exists _mask_buf, _prov_strict, _forest_sub;

-- Máscara de clip: continente + ${BUFFER_M} m, subdividido (piezas ≤256 vért.).
create table _mask_buf as
  select st_subdivide(st_buffer(geom, ${BUFFER_M}), 256) as g from mv_provincia_continental;
create index on _mask_buf using gist (g);

-- Límite provincial ESTRICTO (unión de municipios), subdividido (para el
-- desglose dentro/fuera del buffer).
create table _prov_strict as
  select st_subdivide(st_union(geom), 256) as g from municipio;
create index on _prov_strict using gist (g);

-- Forestal de origen, saneado y subdividido.
create table _forest_sub as
  select st_subdivide(st_makevalid(geom), 256) as g from stg_patfor;
create index on _forest_sub using gist (g);

analyze _mask_buf; analyze _prov_strict; analyze _forest_sub;
SQL

# --- 3b. Clip + carga idempotente (transacción con aserciones) ----------------
echo "==> Recortando (join espacial con &&) + carga…"
psql -v ON_ERROR_STOP=1 <<SQL
\set QUIET on
begin;
truncate terreno_forestal restart identity;

insert into terreno_forestal (geom, modelo_combustible, modelo_norm, source)
select st_multi(g), null, null, 'PATFOR:SF.Forestal (ICV/GVA)'
from (
  select st_collectionextract(st_intersection(f.g, m.g), 3) as g
  from _forest_sub f join _mask_buf m on f.g && m.g
  where st_intersects(f.g, m.g)
) x
where g is not null and not st_isempty(g);

-- ===================== ASERCIONES (fallan ruidosamente) =====================
do \$\$
declare n int; ha numeric;
begin
  select count(*) into n from terreno_forestal;
  if n = 0 then raise exception 'terreno_forestal vacío tras la carga'; end if;

  select count(*) into n from terreno_forestal where st_srid(geom) <> 25830;
  if n <> 0 then raise exception '% geometrías fuera de SRID 25830', n; end if;

  select count(*) into n from terreno_forestal where not st_isvalid(geom);
  if n <> 0 then raise exception '% geometrías inválidas (ST_IsValid)', n; end if;

  -- Nada mar adentro: el extent de terreno_forestal debe caer dentro del extent
  -- del buffer (+1 m tolerancia). Si el clip fallara e incluyera las Columbretes
  -- (X~815520), el extent se saldría del buffer (maxX~802748) y salta. O(1).
  if not st_within(
       st_envelope((select st_extent(geom)::geometry from terreno_forestal)),
       st_expand(st_envelope((select st_extent(g)::geometry from _mask_buf)), 1)
     ) then
    raise exception 'terreno_forestal se sale del continental+${BUFFER_M}m (¿clip mal / mar adentro?)';
  end if;

  -- Total dentro del límite provincial ESTRICTO: plausible 200k..700k ha
  -- (la superficie forestal de Castellón ronda el 55-65% de 663.822 ha).
  select round(sum(st_area(st_intersection(tf.geom, p.g)))/10000) into ha
  from terreno_forestal tf join _prov_strict p on tf.geom && p.g
  where st_intersects(tf.geom, p.g);
  if ha < 200000 or ha > 700000 then
    raise exception 'ha forestal dentro del límite estricto = % fuera de [200000, 700000]', ha;
  end if;
end \$\$;
commit;
SQL
echo "==> terreno_forestal: OK (cargado, recortado y verificado)."

# --- 4. Inventario (LO importante del commit) ---------------------------------
echo "==> Generando $INVENTARIO…"
{
  echo "# Inventario del terreno forestal PATFOR"
  echo
  echo "> Generado por \`make seed\` (etl/load_terreno_forestal.sh). No editar a mano."
  echo
  echo "## Hallazgo clave: esta capa NO contiene modelo de combustible"
  echo
  echo "\`SF.Forestal\` del PATFOR es la capa de **extensión de terreno forestal**."
  echo "Sus únicos atributos son \`forestal\`, \`compatible\` y \`prov\` (+ geometría y"
  echo "\`shape_length\`/\`shape_area\` del origen). Por eso \`modelo_combustible\` y"
  echo "\`modelo_norm\` se cargan a **NULL**: aquí no hay ese dato."
  echo
  echo "El modelo de combustible del PATFOR **sí existe**, en otra capa del mismo"
  echo "WFS: \`ms:Regulacion.Incendios.Combustible\` = «Modelo de combustible"
  echo "(clas. **Rothermel**)». Es **Rothermel (13 modelos), NO Prometheus (7)**"
  echo "que asume docs/04 §2.2. Ver el RIESGO ABIERTO en docs/04."
  echo
  echo "## Atributos reales de la capa de origen (staging \`stg_patfor\`)"
  echo
  echo '```'
} > "$INVENTARIO"
run_sql >> "$INVENTARIO" <<'SQL'
\pset border 2
\echo Columnas (nombre | tipo):
select column_name, data_type from information_schema.columns
where table_name = 'stg_patfor' order by ordinal_position;
\echo
\echo Tres filas de ejemplo (atributos, sin geometría):
select fid, forestal, compatible, prov, shape_length, shape_area from stg_patfor limit 3;
SQL
{
  echo '```'
  echo
  echo "## Campo \`compatible\`: ¿qué significa el 1?"
  echo
  echo "Es **uniforme** en toda la capa. Su significado literal no consta en"
  echo "metadatos legibles (la definición oficial en PDF es una imagen escaneada,"
  echo "sin texto extraíble). En una capa de terreno forestal, un flag uniforme a"
  echo "1 marca todos los polígonos como suelo forestal computable. Si apareciera"
  echo "algún valor distinto de 1, se listaría aquí para no ignorarlo en silencio:"
  echo
  echo '```'
} >> "$INVENTARIO"
run_sql >> "$INVENTARIO" <<'SQL'
\pset border 2
select compatible, count(*) as n from stg_patfor group by 1 order by 2 desc;
select forestal, count(*) as n from stg_patfor group by 1 order by 2 desc;
SQL
{
  echo '```'
  echo
  echo "## Modelo de combustible cargado en \`terreno_forestal\`"
  echo
  echo '```'
} >> "$INVENTARIO"
run_sql >> "$INVENTARIO" <<'SQL'
\pset border 2
select coalesce(modelo_combustible, '(NULL)') as modelo_combustible,
       count(*) as n_poligonos,
       round(sum(st_area(geom))/10000) as ha
from terreno_forestal group by 1 order by 3 desc;
SQL
{
  echo '```'
  echo
  echo "## Hectáreas: total y desglose dentro/fuera del límite provincial estricto"
  echo
  echo "El buffer de 5 km capta combustible al otro lado del límite (continuidad"
  echo "de cara a un GIF fronterizo). Aquí se ve cuánto aporta."
  echo
  echo '```'
} >> "$INVENTARIO"
run_sql >> "$INVENTARIO" <<'SQL'
\pset border 2
with dentro as (
  select round(sum(st_area(st_intersection(tf.geom, p.g)))/10000) as ha
  from terreno_forestal tf join _prov_strict p on tf.geom && p.g
  where st_intersects(tf.geom, p.g)
),
total as (select round(sum(st_area(geom))/10000) as ha from terreno_forestal)
select total.ha        as ha_total_cargado,
       dentro.ha       as ha_dentro_limite_estricto,
       total.ha - dentro.ha as ha_aportadas_por_buffer
from total, dentro;
SQL
echo '```' >> "$INVENTARIO"
echo "==> inventario escrito en $INVENTARIO."

# --- 5. Limpieza de máscaras auxiliares ---------------------------------------
run_sql -c "drop table if exists _mask_buf, _prov_strict, _forest_sub, stg_patfor;"
