-- V13 · Vista materializada del índice del día más reciente por municipio. Es lo
-- que sirve el visor coropleto (unida por ine_code a la tesela MVT, ADR-06): solo
-- los datos DINÁMICOS del índice; nombre/comarca los da /municipios (inmutable).
-- La refresca IndiceService al final del cálculo diario.
--
-- Se crea WITH DATA (0 filas al migrar, indice_peligro aún vacío). El índice único
-- por ine_code permite REFRESH ... CONCURRENTLY si algún día hiciera falta.
create materialized view mv_indice_hoy as
select ip.ine_code,
       ip.fecha,
       ip.comp_meteo,
       ip.comp_estructural,
       ip.comp_vulnerab,
       ip.indice,
       ip.nivel,
       ip.alerta_30_30_30,
       ip.viento_alineado,
       ip.version_modelo
from indice_peligro ip
join (
  select ine_code, max(fecha) as fecha from indice_peligro group by ine_code
) ult using (ine_code, fecha);

create unique index mv_indice_hoy_pk on mv_indice_hoy (ine_code);
