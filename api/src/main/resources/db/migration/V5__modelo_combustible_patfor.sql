-- V5 · Modelo de combustible del PATFOR (capa Regulacion.Incendios.Combustible,
-- esquema Anderson/Rothermel). Tabla PROPIA, no columna de terreno_forestal:
-- son productos cartográficos distintos y su overlay (asignar modelo a cada
-- polígono forestal) es Fase 3. codigo_origen = valor crudo `mod_comb` sin
-- interpretar (Anderson 2..8; el 0 = no combustible se excluye en la carga).
-- Recortado a continental + 5 km, como SF.Forestal. Ver load_modelo_combustible.sh,
-- docs/04 §2.2 y etl/reports/patfor-combustible-inventario.md.
create table modelo_combustible_patfor (
  id            bigserial primary key,
  codigo_origen text not null,       -- mod_comb crudo (2..8); 0 (no comb.) excluido
  geom          geometry(MultiPolygon, 25830) not null,
  source        text not null,
  fetched_at    timestamptz not null default now(),
  constraint mc_srid check (st_srid(geom) = 25830)
);
create index modelo_combustible_patfor_gix
  on modelo_combustible_patfor using gist (geom);
