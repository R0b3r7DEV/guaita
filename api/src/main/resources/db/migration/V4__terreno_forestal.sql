-- V4 · Terreno forestal (capa de EXTENSIÓN) del PATFOR, recortado a la
-- geometría de trabajo continental + 5 km. Ver etl/load_terreno_forestal.sh.
--
-- modelo_combustible / modelo_norm quedan NULL a propósito: la capa SF.Forestal
-- del PATFOR NO trae modelos de combustible. El modelo existe en OTRA capa del
-- WFS (Regulacion.Incendios.Combustible, clas. Rothermel), que es un producto
-- aparte y se aborda en Fase 3. Ver docs/04 §2.2 (RIESGO ABIERTO) y docs/02.
create table terreno_forestal (
  id                 bigserial primary key,
  modelo_combustible text,                      -- NULL en Fase 1 (no está en SF.Forestal)
  modelo_norm        smallint,                  -- NULL; normalización a inflamabilidad = Fase 3
  geom               geometry(MultiPolygon, 25830) not null,
  source             text not null,
  fetched_at         timestamptz not null default now(),
  constraint tf_srid check (st_srid(geom) = 25830)
);
create index tf_geom_gix on terreno_forestal using gist (geom);
