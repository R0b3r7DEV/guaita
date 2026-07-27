-- V3 · Geometría de trabajo CONTINENTAL de la provincia.
--
-- La geometría administrativa (tabla municipio) es correcta y NO se toca: el
-- término de Castelló de la Plana (INE 12040) incluye legítimamente el
-- archipiélago de las Columbretes (~19,7 ha emergidas, a ~56 km de la costa).
-- Su punto más oriental lleva el máximo X provincial a ~815.520 en 25830.
--
-- Pero derivar hojas MTN50, clipar el PATFOR o encuadrar el visor sobre esa
-- geometría arrastraría decenas de km de mar abierto por cuatro peñascos de
-- 67 m. Esta vista materializada aísla la MASA CONTINENTAL: la provincia
-- disuelta MENOS los polígonos insulares. Su máximo X baja a ~797.748.
--
-- Criterio (elegido con datos reales, no a ojo): se descartan los polígonos
-- cuya distancia a la mayor masa (el continente) supera UMBRAL_ISLA_M. Medido
-- sobre la geometría real: los polígonos continentales están a 0 km y las
-- Columbretes a 54,9–56,8 km; 10 km cae limpiamente en ese hueco. El umbral
-- se cambia SOLO aquí. Ver docs/03-modelo-datos.md.
create materialized view mv_provincia_continental as
with poligonos as (
  -- provincia disuelta -> el continente es UN polígono; las islas, aparte
  select (st_dump(st_union(geom))).geom as g from municipio
),
continente as (
  select g from poligonos order by st_area(g) desc limit 1
)
select st_multi(st_union(p.g)) as geom
from poligonos p, continente c
where st_dwithin(p.g, c.g, 10000)          -- UMBRAL_ISLA_M = 10 km
;

create index mv_provincia_continental_gix
  on mv_provincia_continental using gist (geom);
