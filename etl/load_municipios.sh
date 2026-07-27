#!/usr/bin/env bash
# Carga de municipios (Fase 1). Llamado por seed.sh dentro del contenedor etl.
# Idempotente. LEE el CSV de comarcas committeado (no lo genera: eso es
# `make comarcas`). Une por ine_code tres fuentes verificadas + geometría:
#   - geometría + nationalCode: CNIG, GML INSPIRE AU (EPSG:4258 -> 25830)
#   - nombre:    del propio GML (parse_nombres.py; ine_code = right(nationalCode,5))
#   - comarca:   etl/mappings/comarcas_castellon.csv (PEGV, committeado)
#   - poblacion: INE padrón pobmun (CPRO+CMUN son campos dedicados; se prefiere
#               a la tabla 2865, cuyo ine_code habría que parsearlo de una cadena
#               de presentación tipo "12126 Vall d'Uixó, la").
set -euo pipefail

DATA=/data
MAP=/etl/mappings
COMARCAS_CSV="$MAP/comarcas_castellon.csv"
CNIG_URL="https://centrodedescargas.cnig.es/CentroDescargas/documentos/atom/au/lineas_limite_gml.zip"
INE_POBMUN_URL="https://www.ine.es/pob_xls/pobmun.zip"
CNIG_ZIP="$DATA/lineas_limite_gml.zip"
GML_DIR="$DATA/lineas_limite_gml"

PG="PG:host=$PGHOST port=$PGPORT dbname=$PGDATABASE user=$PGUSER password=$PGPASSWORD"
run_sql() { psql -v ON_ERROR_STOP=1 -q "$@"; }

# --- 0. Guard: el CSV de comarcas debe existir (make comarcas primero) --------
if [[ ! -f "$COMARCAS_CSV" ]]; then
  echo "ERROR: falta $COMARCAS_CSV." >&2
  echo "       Ejecuta 'make comarcas' primero (lo genera desde el PEGV, se" >&2
  echo "       revisa el diff y se commitea). seed NO lo genera al vuelo." >&2
  exit 1
fi

# --- 1. Descarga de límites municipales del CNIG (cache) ----------------------
if [[ ! -f "$CNIG_ZIP" ]]; then
  echo "==> Descargando límites municipales del CNIG…"
  curl -fsSL -o "$CNIG_ZIP" "$CNIG_URL"
fi
rm -rf "$GML_DIR"; mkdir -p "$GML_DIR"
unzip -o -q "$CNIG_ZIP" -d "$GML_DIR"

# El GML de unidades municipales (recintos, superficial) del esquema INSPIRE AU:
mapfile -t MUNI_GML < <(find "$GML_DIR" -iname 'au_AdministrativeUnit_4thOrder*.gml' | sort)
if [[ ${#MUNI_GML[@]} -eq 0 ]]; then
  echo "ERROR: no encuentro au_AdministrativeUnit_4thOrder*.gml en el ZIP." >&2
  find "$GML_DIR" -iname '*.gml' -printf '   %f\n' >&2
  exit 1
fi
echo "==> GML municipal: $(basename "${MUNI_GML[0]}")"

# --- 2. Datos tabulares por ine_code (parsers verificados) --------------------
echo "==> Descargando y parseando el padrón del INE (pobmun)…"
curl -fsSL -o "$DATA/pobmun.zip" "$INE_POBMUN_URL"
python3 /etl/parse_poblacion.py "$DATA/pobmun.zip" "$DATA/poblacion.csv"

echo "==> Extrayendo nombres del GML…"
python3 /etl/parse_nombres.py "${MUNI_GML[0]}" "$DATA/nombres.csv"

# Quitar comentarios del CSV de comarcas para \copy (deja solo header + datos):
grep -v '^#' "$COMARCAS_CSV" > "$DATA/comarcas_clean.csv"

# --- 3. Geometría a staging: reproyectar 4258 -> 25830, solo nationalCode -----
echo "==> Cargando recintos municipales a staging (ogr2ogr, 4258 -> 25830)…"
run_sql -c "drop table if exists stg_muni;"
for i in "${!MUNI_GML[@]}"; do
  mode="-append"; [[ $i -eq 0 ]] && mode="-overwrite"
  ogr2ogr -f PostgreSQL "$PG" "${MUNI_GML[$i]}" AdministrativeUnit \
    -nln stg_muni $mode \
    -s_srs EPSG:4258 -t_srs EPSG:25830 \
    -nlt MULTIPOLYGON \
    -select nationalCode \
    -lco GEOMETRY_NAME=geom -lco FID=gid --config PG_USE_COPY YES
done

# Diagnóstico empírico (primera vez): formato real del nationalCode.
echo "==> Muestra de nationalCode (ine_code = últimos 5):"
run_sql -c "select nationalcode, right(nationalcode::text,5) as ine_code from stg_muni limit 3;"

# --- 4. Construir municipio (idempotente) + aserciones ------------------------
echo "==> Construyendo tabla municipio + aserciones de integridad…"
psql -v ON_ERROR_STOP=1 <<SQL
\set QUIET on
begin;

create temp table tmp_comarca(ine_code text, municipio text, comarca text);
\copy tmp_comarca from '$DATA/comarcas_clean.csv' with (format csv, header true)

create temp table tmp_nombre(ine_code text, nombre text);
\copy tmp_nombre from '$DATA/nombres.csv' with (format csv, header true)

create temp table tmp_pob(ine_code text, poblacion integer);
\copy tmp_pob from '$DATA/poblacion.csv' with (format csv, header true)

truncate municipio;

insert into municipio (ine_code, nombre, nombre_va, comarca, geom, superficie_ha, poblacion)
select g.ine_code,
       n.nombre,
       null,                                   -- nombre_va: Fase 1 no lo separa
       c.comarca,
       g.geom,
       round((st_area(g.geom) / 10000.0)::numeric, 2),
       p.poblacion
from (
  select right(nationalcode::text, 5) as ine_code,
         st_multi(st_union(geom)) as geom      -- une features partidas de un mismo muni
  from stg_muni
  where right(nationalcode::text, 5) like '12%'
  group by right(nationalcode::text, 5)
) g
join tmp_nombre  n using (ine_code)
join tmp_comarca c using (ine_code)
join tmp_pob     p using (ine_code);

-- ===================== ASERCIONES (fallan ruidosamente) =====================
do \$\$
declare n int; m int;
begin
  select count(*) into n from municipio;
  if n <> 135 then raise exception 'municipio tiene % filas, esperaba 135', n; end if;

  select count(*) into n from municipio where st_srid(geom) <> 25830;
  if n <> 0 then raise exception '% geometrías fuera de SRID 25830', n; end if;

  select count(*) into n from municipio where not st_isvalid(geom);
  if n <> 0 then raise exception '% geometrías inválidas (ST_IsValid)', n; end if;

  select count(*) into n from municipio where ine_code not like '12%';
  if n <> 0 then raise exception '% ine_code no empiezan por 12 (provincia Castellón)', n; end if;

  select count(distinct ine_code) into n from municipio;
  if n <> 135 then raise exception '% ine_code distintos, esperaba 135', n; end if;

  select count(*) into n from municipio where poblacion is null or poblacion <= 0;
  if n <> 0 then raise exception '% municipios con poblacion nula o <= 0', n; end if;

  -- Comarca: el CSV committeado debe tener 135 filas y casar 1:1 con municipio.
  select count(*) into n from tmp_comarca;
  if n <> 135 then raise exception 'comarcas_castellon.csv tiene % filas, esperaba 135', n; end if;
  select count(*) into n from (
    select ine_code from municipio except select ine_code from tmp_comarca) x;
  select count(*) into m from (
    select ine_code from tmp_comarca except select ine_code from municipio) y;
  if n <> 0 or m <> 0 then
    raise exception 'ine_code de comarcas no casan 1:1 con municipio (municipio-comarca=%, comarca-municipio=%)', n, m;
  end if;

  -- Encoding: los tres nombres de control deben existir tal cual.
  if not exists (select 1 from municipio where nombre = 'Alcalà de Xivert') then
    raise exception 'falta/mal codificado "Alcalà de Xivert"'; end if;
  if not exists (select 1 from municipio where nombre = 'la Vall d''Uixó') then
    raise exception 'falta/mal codificado "la Vall d''Uixó"'; end if;
  if not exists (select 1 from municipio where nombre = 'l''Alcora') then
    raise exception 'falta/mal codificado "l''Alcora"'; end if;

  -- Extent ADMINISTRATIVO: incluye las Columbretes (INE 12040), que llevan el
  -- máximo X provincial a ~815.520. Sobre con margen; una inversión de ejes
  -- lat/lon daría valores absurdos, fuera de aquí. (La aserción CONTINENTAL,
  -- más estricta, va aparte tras refrescar mv_provincia_continental.)
  if not (
    select st_xmin(e) > 660000 and st_xmax(e) < 830000
       and st_ymin(e) > 4380000 and st_ymax(e) < 4540000
    from (select st_extent(geom) e from municipio) t
  ) then
    raise exception 'extent administrativo fuera del sobre de Castellón (¿ejes/proyección?)';
  end if;
end \$\$;

commit;
SQL

echo "==> municipios: OK (135 términos cargados y verificados)."

# --- 5. Geometría de trabajo continental (excluye Columbretes) ----------------
echo "==> Refrescando mv_provincia_continental (aísla las islas)…"
run_sql -c "refresh materialized view mv_provincia_continental;"
run_sql <<'SQL'
do $$
declare cx numeric; ax numeric;
begin
  select st_xmax(st_extent(geom)) into cx from mv_provincia_continental;
  select st_xmax(st_extent(geom)) into ax from municipio;
  if cx is null then raise exception 'mv_provincia_continental vacía'; end if;

  -- Extent CONTINENTAL: sin las Columbretes el máximo X baja a ~797.748.
  -- Sobre estrecho: si las islas se colaran, cx pasaría de 800000 y saltaría.
  if cx < 660000 or cx > 800000 then
    raise exception 'extent continental maxX=% fuera de rango (¿islas no excluidas o ejes?)', round(cx);
  end if;

  -- Discriminante entre ambas geometrías: la administrativa DEBE superar a la
  -- continental por el este (las islas). Si fueran iguales, la exclusión falló.
  if ax <= cx then
    raise exception 'admin maxX=% no supera continental maxX=%: la exclusión de islas no funcionó', round(ax), round(cx);
  end if;
end $$;
SQL
echo "==> geometría continental: OK (maxX continental < administrativo; islas aisladas)."
