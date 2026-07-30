-- V8 · Fase 2: calidadDato para fuente en REJILLA (ADR-07). El reanálisis no tiene estaciones, así
-- que interpolado/n_estaciones ya no significaban nada. Se sustituyen por la magnitud del
-- downscaling altitudinal que hace Open-Meteo: cuánto separa la cota NATIVA de su celda de la
-- altitud media del municipio. A mayor |delta_altitud_m|, menos fiable el dato (docs/04, docs/06).
--
-- meteo_municipio está VACÍA todavía (el backfill es de un commit posterior), así que se pueden
-- añadir columnas NOT NULL sin default sin romper filas existentes.
alter table meteo_municipio drop column interpolado;
alter table meteo_municipio drop column n_estaciones;

-- Cota nativa del modelo en el punto de consulta (m). Estática por municipio.
alter table meteo_municipio add column elevacion_celda_m numeric(7, 2) not null;

-- altitud_media_m - elevacion_celda_m. Signo: municipio más alto que la celda -> delta positivo.
alter table meteo_municipio add column delta_altitud_m numeric(7, 2) not null;
