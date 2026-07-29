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

# --- 3. Clip contra continental + buffer, ST_Subdivide, carga idempotente -----
echo "==> Recortando contra continental + ${BUFFER_M} m y aplicando ST_Subdivide (≤256 vértices)…"
psql -v ON_ERROR_STOP=1 <<SQL
\set QUIET on
begin;
truncate terreno_forestal restart identity;

with buf as (
  select st_buffer(geom, ${BUFFER_M}) as g from mv_provincia_continental
),
clipped as (
  select st_collectionextract(st_makevalid(st_intersection(s.geom, b.g)), 3) as geom
  from stg_patfor s, buf b
  where st_intersects(s.geom, b.g)
)
insert into terreno_forestal (geom, modelo_combustible, modelo_norm, source)
select st_multi(st_subdivide(geom, 256)), null, null, 'PATFOR:SF.Forestal (ICV/GVA)'
from clipped
where geom is not null and not st_isempty(geom);

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

  -- Ninguna geometría fuera del continental + buffer (si el clip hubiera fallado).
  select count(*) into n
  from terreno_forestal tf,
       (select st_buffer(geom, ${BUFFER_M}) g from mv_provincia_continental) b
  where not st_coveredby(tf.geom, b.g);
  if n <> 0 then raise exception '% geometrías fuera de continental+${BUFFER_M}m (¿clip mal?)', n; end if;

  -- Total dentro del límite provincial ESTRICTO: plausible 200k..700k ha
  -- (la superficie forestal de Castellón ronda el 55-65% de 663.822 ha).
  select round(sum(st_area(st_intersection(tf.geom, prov.g)))/10000) into ha
  from terreno_forestal tf, (select st_union(geom) g from municipio) prov
  where st_intersects(tf.geom, prov.g);
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
with prov as (select st_union(geom) g from municipio)
select
  round(sum(st_area(tf.geom))/10000)                                   as ha_total_cargado,
  round(sum(st_area(st_intersection(tf.geom, prov.g)))/10000)          as ha_dentro_limite_estricto,
  round((sum(st_area(tf.geom)) - sum(st_area(st_intersection(tf.geom, prov.g))))/10000)
                                                                       as ha_aportadas_por_buffer
from terreno_forestal tf, prov;
SQL
echo '```' >> "$INVENTARIO"
echo "==> inventario escrito en $INVENTARIO."
