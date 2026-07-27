-- V2 · Tabla municipio: unidad de análisis (135 términos de Castellón).
-- La carga (make seed) es responsabilidad del ETL; aquí solo el esquema.
-- SRID de trabajo 25830 (ETRS89 / UTM 30N), con CHECK explícito.
--
-- poblacion es NOT NULL con CHECK (>0): entra en comp_vulnerab (doc 04, Fase 3)
-- y un NULL/0 se propagaría en silencio como "municipio deshabitado". Se corta
-- en el esquema, no solo en las aserciones del seed. (Diverge del DDL original
-- del doc 03, que se actualiza en consecuencia: los docs son la fuente de verdad.)
create table municipio (
  ine_code        char(5) primary key,                 -- código INE (prov 12 + muni)
  nombre          text not null,
  nombre_va       text,                                 -- nombre en valenciano (si aplica)
  comarca         text not null,                        -- desde PEGV (etl/mappings)
  geom            geometry(MultiPolygon, 25830) not null,
  superficie_ha   numeric(10,2) not null,               -- ST_Area(geom)/10000, en 25830
  poblacion       integer not null,
  constraint municipio_srid check (st_srid(geom) = 25830),
  constraint municipio_poblacion_pos check (poblacion > 0)
);

create index municipio_geom_gix on municipio using gist (geom);
