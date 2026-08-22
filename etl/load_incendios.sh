#!/usr/bin/env bash
# Carga de perímetros históricos de incendio -> incendio_historico (Fase 3, f_tiempo).
# Llamado por seed.sh dentro del contenedor etl. Idempotente.
#
# DOS caminos en el mismo script:
#   1. SEMILLAS (siempre): 4 eventos que conocemos (etl/mappings/incendios_semilla.csv),
#      con la geometría del término afectado como localizador grueso. Dan f_tiempo real
#      a los grandes incendios recientes mientras EFFIS no es accesible.
#   2. EFFIS/GWIS (si hay fichero en /data): perímetros reales. Reproyecta a 25830 con
#      verificación empírica, recorta contra mv_provincia_continental + 5 km, reconcilia
#      con las semillas (solape espacial + fecha -> mismo evento, EFFIS manda) y carga.
#      El mapeo de campos vive en etl/mappings/incendios-effis.env (no incrustado).
#
# --- Comprobación del WFS de EFFIS (hazla al empezar cada sesión) -------------
# El backend Oracle del WFS ercc.ba lleva caído toda la Fase 3. Un intento:
#   curl -s --max-time 60 "https://ies-ows.jrc.ec.europa.eu/effis?service=WFS&\
#version=1.1.0&request=GetFeature&typename=ercc.ba&srsname=EPSG:4326&\
#bbox=39.7,-0.8,40.8,0.3,EPSG:4326&maxfeatures=1&outputformat=geojson"
# Si devuelve un FeatureCollection (no 'OracleSpatial error'/'Exception'), revivió:
# descárgalo a data/incendios.gpkg y re-ejecuta `make seed`.
set -euo pipefail

DATA=/data
REPORTS=/etl/reports
SEMILLAS=/etl/mappings/incendios_semilla.csv
MAPEO=/etl/mappings/incendios-effis.env
INVENTARIO="$REPORTS/incendios-inventario.md"
BUFFER_M=5000
PG="PG:host=$PGHOST port=$PGPORT dbname=$PGDATABASE user=$PGUSER password=$PGPASSWORD"
run_sql() { psql -v ON_ERROR_STOP=1 -q "$@"; }

mkdir -p "$DATA" "$REPORTS"

# --- 1. Semillas (idempotente) -----------------------------------------------
echo "==> Cargando semillas de incendio ($SEMILLAS)…"
psql -v ON_ERROR_STOP=1 <<SQL
\set QUIET on
begin;
drop table if exists stg_semilla;
create temp table stg_semilla (
  nombre text, fecha_inicio date, fecha_fin date,
  superficie_ha numeric, ine_code char(5), source text);
\copy stg_semilla from '$SEMILLAS' with (format csv, header true, null '')

-- Todos los ine_code de la semilla deben existir (si no, error de dato).
do \$\$
declare n int;
begin
  select count(*) into n
  from stg_semilla s left join municipio m on m.ine_code = s.ine_code
  where m.ine_code is null;
  if n <> 0 then raise exception '% ine_code de semilla no existen en municipio', n; end if;
end \$\$;

-- Re-carga limpia de las semillas; las filas EFFIS (es_semilla=false) no se tocan.
delete from incendio_historico where es_semilla;
insert into incendio_historico
  (nombre, fecha_inicio, fecha_fin, superficie_ha, geom, es_semilla, source)
select s.nombre, s.fecha_inicio, max(s.fecha_fin), max(s.superficie_ha),
       st_multi(st_union(m.geom)), true, max(s.source)
from stg_semilla s join municipio m on m.ine_code = s.ine_code
group by s.nombre, s.fecha_inicio;
commit;
SQL

# --- 2. Perímetros EFFIS/GWIS (solo si hay fichero) --------------------------
INCENDIOS_FILE="${INCENDIOS_FILE:-}"
if [[ -z "$INCENDIOS_FILE" ]]; then
  INCENDIOS_FILE=$(ls "$DATA"/incendios.gpkg "$DATA"/incendios.shp 2>/dev/null | head -1 || true)
fi

if [[ -n "$INCENDIOS_FILE" && -f "$INCENDIOS_FILE" ]]; then
  echo "==> Fichero EFFIS/GWIS detectado: $INCENDIOS_FILE"
  # shellcheck disable=SC1090
  source "$MAPEO"
  SRC="EFFIS/GWIS ($(basename "$INCENDIOS_FILE"))"

  # Columnas OPCIONALES (nombre, fecha_fin): expresión SQL si están mapeadas,
  # 'null' si no. Se precomputan aquí para no liar la expansión dentro del SQL.
  NOMBRE_EXPR=${FLD_NOMBRE:+i.$FLD_NOMBRE::text}; NOMBRE_EXPR=${NOMBRE_EXPR:-null}
  FIN_EXPR=${FLD_FECHA_FIN:+i.$FLD_FECHA_FIN::date}; FIN_EXPR=${FIN_EXPR:-null}

  # 2a. SRID declarado (empírico) + carga a staging forzando 25830.
  echo "==> SRID declarado por el fichero:"
  ogrinfo -so -al "$INCENDIOS_FILE" ${INCENDIOS_LAYER:+"$INCENDIOS_LAYER"} 2>/dev/null \
    | grep -iE "Layer SRS|PROJCRS|GEOGCRS|ID\[\"EPSG" | head -4 || true

  run_sql -c "drop table if exists stg_incendios;"
  # -t_srs falla ruidosamente si el fichero no declara CRS: es lo que queremos.
  ogr2ogr -f PostgreSQL "$PG" "$INCENDIOS_FILE" ${INCENDIOS_LAYER:+"$INCENDIOS_LAYER"} \
    -nln stg_incendios -overwrite \
    -t_srs EPSG:25830 -nlt MULTIPOLYGON \
    -lco GEOMETRY_NAME=geom --config PG_USE_COPY YES \
    ${FLD_PAIS:+-where "${FLD_PAIS}='${PAIS_VALOR}'"}

  # 2b. Verificación empírica: el resultado DEBE estar en 25830 y en el sitio.
  psql -v ON_ERROR_STOP=1 -v src="$SRC" <<SQL
\set QUIET on
do \$\$
declare n int;
begin
  select count(*) into n from stg_incendios where st_srid(geom) <> 25830;
  if n <> 0 then raise exception '% geometrías EFFIS fuera de SRID 25830 tras -t_srs', n; end if;
end \$\$;
SQL

  # 2c. Recorte + reconciliación + carga idempotente (una transacción).
  echo "==> Recortando (continental + ${BUFFER_M} m), reconciliando y cargando…"
  psql -v ON_ERROR_STOP=1 -v src="$SRC" <<SQL
\set QUIET on
begin;

-- Máscara de clip (continente + buffer), subdividida e indexada.
drop table if exists _mask_inc;
create temp table _mask_inc as
  select st_subdivide(st_buffer(geom, ${BUFFER_M}), 256) as g from mv_provincia_continental;
create index on _mask_inc using gist (g);

-- Perímetros recortados al ámbito, con los campos mapeados.
drop table if exists _eff;
create temp table _eff as
select
  ${NOMBRE_EXPR} as nombre,
  i.${FLD_FECHA_INICIO}::date as fecha_inicio,
  ${FIN_EXPR} as fecha_fin,
  (i.${FLD_SUPERFICIE_HA}::numeric * ${SUPERFICIE_A_HA}) as superficie_ha,
  i.${FLD_EXT_ID}::text as ext_id,
  st_multi(st_collectionextract(st_intersection(st_makevalid(i.geom), m.g), 3)) as geom
from stg_incendios i join _mask_inc m on st_makevalid(i.geom) && m.g
where st_intersects(st_makevalid(i.geom), m.g);

delete from _eff where geom is null or st_isempty(geom);

-- Reconciliación: una semilla que solapa + coincide en fecha (±15 d) con un
-- perímetro EFFIS es el MISMO evento -> la semilla sobra (EFFIS manda). Se
-- anotan las discrepancias de superficie antes de borrarla.
drop table if exists _recon;
create temp table _recon as
select distinct se.id as seed_id, se.nombre as nombre_semilla,
       se.superficie_ha as ha_semilla, e.superficie_ha as ha_effis
from incendio_historico se
join _eff e on se.es_semilla
  and st_intersects(se.geom, e.geom)
  and abs(e.fecha_inicio - se.fecha_inicio) <= 15;

delete from incendio_historico se using _recon r where se.id = r.seed_id;

-- Carga idempotente: se re-borra lo de esta fuente y se reinserta.
delete from incendio_historico where not es_semilla and source = :'src';
insert into incendio_historico
  (nombre, fecha_inicio, fecha_fin, superficie_ha, geom, es_semilla, ext_id, source)
select coalesce(nombre, 'EFFIS ' || ext_id), fecha_inicio, fecha_fin, superficie_ha,
       geom, false, ext_id, :'src'
from _eff;

-- ================= ASERCIONES c) y d) (solo con fichero real) ===============
do \$\$
declare n int; ymin int; ymax int;
begin
  -- c) cobertura temporal razonable dentro de 2000..2026.
  select min(extract(year from fecha_inicio))::int,
         max(extract(year from fecha_inicio))::int
    into ymin, ymax
  from incendio_historico where not es_semilla;
  if ymin is null then raise exception 'EFFIS cargado pero 0 perímetros'; end if;
  if ymin < 2000 or ymax > 2026 then
    raise exception 'años EFFIS fuera de [2000,2026]: % .. %', ymin, ymax;
  end if;
end \$\$;
commit;
SQL

else
  echo "==> Sin fichero EFFIS/GWIS en $DATA (INCENDIOS_FILE vacío)."
  echo "    f_tiempo funciona con las semillas; el resto de municipios queda neutro"
  echo "    (sin-dato-valor=1.0). Cuando llegue el fichero: colócalo en"
  echo "    data/incendios.gpkg, ajusta $MAPEO y re-ejecuta 'make seed'."
fi

# --- 3. Aserciones base (siempre) --------------------------------------------
echo "==> Verificando (los 10 municipios conocidos y las superficies citables)…"
psql -v ON_ERROR_STOP=1 <<'SQL'
\set QUIET on
do $$
declare faltan text; ha_bejis numeric; ha_villa numeric;
begin
  -- a) los 10 municipios de los eventos conocidos están cubiertos por algún
  --    perímetro (semilla o EFFIS). En particular el clúster de l'Alcalatén
  --    (12122/12049/12060/12072), que EFFIS podría registrar por debajo del
  --    umbral de área si lo trocea por término.
  select string_agg(c.ine_code, ', ') into faltan
  from (values ('12022'),('12071'),('12140'),('12114'),
               ('12122'),('12049'),('12060'),('12072'),
               ('12133'),('12126')) as c(ine_code)
  join municipio m on m.ine_code = c.ine_code
  where not exists (
    select 1 from incendio_historico i where st_intersects(i.geom, m.geom));
  if faltan is not null then
    raise exception 'municipios de eventos conocidos SIN perímetro: %', faltan;
  end if;

  -- b) Bejís ~19.000 ha y Villanueva de Viver ~4.700 ha (tolerancia de orden de
  --    magnitud: detecta un fallo de unidades o de recorte, no un ajuste fino).
  select max(i.superficie_ha) into ha_bejis
  from incendio_historico i join municipio m on m.ine_code = '12022'
  where st_intersects(i.geom, m.geom);
  if ha_bejis is null or ha_bejis < 8000 or ha_bejis > 40000 then
    raise exception 'superficie de Bejís = % ha, fuera de [8000, 40000]', ha_bejis;
  end if;

  select max(i.superficie_ha) into ha_villa
  from incendio_historico i join municipio m on m.ine_code = '12133'
  where st_intersects(i.geom, m.geom);
  if ha_villa is null or ha_villa < 2000 or ha_villa > 10000 then
    raise exception 'superficie de Villanueva = % ha, fuera de [2000, 10000]', ha_villa;
  end if;
end $$;
SQL
echo "==> incendio_historico: OK."

# --- 4. Inventario (recuento por año = aserción d, informativa) ---------------
echo "==> Generando $INVENTARIO…"
{
  echo "# Inventario de perímetros históricos de incendio"
  echo
  echo "> Generado por \`make seed\` (etl/load_incendios.sh). No editar a mano."
  echo
  echo "## Procedencia y recuento por año"
  echo
  echo '```'
} > "$INVENTARIO"
run_sql >> "$INVENTARIO" <<'SQL'
\pset border 2
select case when es_semilla then 'semilla' else 'EFFIS/GWIS' end as origen,
       count(*) as n, min(fecha_inicio) as desde, max(fecha_inicio) as hasta
from incendio_historico group by 1 order by 1;
\echo
\echo Recuento por año (d: 2012, 2022 y 2023 deberían destacar con EFFIS):
select extract(year from fecha_inicio)::int as anio, count(*) as n,
       round(sum(superficie_ha)) as ha_total
from incendio_historico group by 1 order by 1;
SQL
echo '```' >> "$INVENTARIO"
echo "==> inventario escrito en $INVENTARIO."

# --- 5. Limpieza ------------------------------------------------------------
run_sql -c "drop table if exists stg_incendios;"
