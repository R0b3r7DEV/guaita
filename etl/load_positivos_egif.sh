#!/usr/bin/env bash
# Carga de positivos del backtest (docs/09): incendios >=100 ha de Castellón según
# la EGIF/MITECO 2005-2022 -> egif_incendio. Idempotente.
#
# El CSV (etl/mappings/positivos_egif_castellon.csv) se generó parseando el XML de
# la herramienta oficial (servicio.mapa.gob.es/incendios) con la superficie FORESTAL
# (arbolada+no arbolada) como total. Término de inicio: por idmunicipio (1..899);
# para el parte multi-término 12999 (fuego de 2012, 10.613 ha) se recupera por sus
# coordenadas (punto de inicio dentro de un municipio).
set -euo pipefail

CSV=/etl/mappings/positivos_egif_castellon.csv
DOC="https://www.miteco.gob.es/es/biodiversidad/temas/incendios-forestales/estadisticas-datos.html"
run_sql() { psql -v ON_ERROR_STOP=1 -q "$@"; }

echo "==> Cargando positivos EGIF ($CSV)…"
psql -v ON_ERROR_STOP=1 -v doc="$DOC" <<SQL
\set QUIET on
begin;
drop table if exists stg_egif;
create temp table stg_egif (
  numeroparte bigint, fecha date, fecha_fin date, superficie_ha numeric,
  idmun int, lat text, lon text, nmun int);
\copy stg_egif from '$CSV' with (format csv, header true, delimiter ';', null '')

truncate egif_incendio;
insert into egif_incendio
  (numeroparte, ine_inicio, fecha_inicio, fecha_fin, superficie_ha, num_municipios, geom,
   source, source_url)
select s.numeroparte,
  coalesce(
    case when s.idmun between 1 and 899 then '12' || lpad(s.idmun::text, 3, '0') end,
    case when s.lat is not null and s.lat <> '' then
      (select m.ine_code from municipio m
       order by m.geom <-> st_transform(
         st_setsrid(st_makepoint(s.lon::float8, s.lat::float8), 4326), 25830)
       limit 1) end
  ) as ine_inicio,
  s.fecha, s.fecha_fin, s.superficie_ha, s.nmun,
  case when s.lat is not null and s.lat <> '' then
    st_transform(st_setsrid(st_makepoint(s.lon::float8, s.lat::float8), 4326), 25830) end,
  'EGIF (MITECO) 2005-2022', :'doc'
from stg_egif s;

-- ================= ASERCIONES (fallan ruidosamente) =====================
do \$\$
declare n int; ha numeric; ine text;
begin
  select count(*) into n from egif_incendio;
  if n <> 26 then raise exception 'esperaba 26 positivos, hay %', n; end if;

  select count(*) into n from egif_incendio e
  where not exists (select 1 from municipio m where m.ine_code = e.ine_inicio);
  if n <> 0 then raise exception '% positivos con ine_inicio inexistente', n; end if;

  -- Bejís 2022 (parte 2022120052): término 12022, superficie forestal ~16.836 ha.
  select ine_inicio, superficie_ha into ine, ha from egif_incendio where numeroparte = 2022120052;
  if ine <> '12022' or abs(ha - 16836.5) > 1 then
    raise exception 'Bejís mal cargado: ine=% ha=%', ine, ha;
  end if;

  -- El 12999 (parte 2012120050) debe haberse resuelto a un municipio real por coords.
  select ine_inicio into ine from egif_incendio where numeroparte = 2012120050;
  if ine is null or ine = '12999' then
    raise exception 'el parte 2012120050 no se resolvió por coordenadas: %', ine;
  end if;
  raise notice 'parte multi-término 2012 (10.613 ha) resuelto a %', ine;
end \$\$;
commit;
SQL
echo "==> egif_incendio: OK (26 positivos, término de inicio resuelto)."
