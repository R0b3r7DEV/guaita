-- V11 · Perímetros históricos de incendio. Alimentan f_tiempo (docs/04 §2.4):
-- años desde el último incendio por municipio, con el criterio de reparto
-- (>= reparto-min-frac-forestal de la superficie forestal municipal).
--
-- Dos procedencias en la MISMA tabla:
--   * EFFIS/GWIS (es_semilla = false): perímetro real, ext_id del producto.
--   * Semillas (es_semilla = true): eventos que conocemos mientras EFFIS no es
--     accesible (WFS con backend Oracle caído; la descarga directa de GWIS solo
--     cubre 2000-2021). La geometría de la semilla es el término afectado como
--     LOCALIZADOR GRUESO, no el perímetro real; por eso su superficie NO es
--     ST_Area(geom) sino la conocida (o NULL si no está confirmada — no se
--     inventa). Cuando llegue el fichero, el perímetro real sustituye la semilla
--     (reconciliación por solape espacial + fecha) y su superficie manda.
create table incendio_historico (
  id            bigint generated always as identity primary key,
  nombre        text not null,
  fecha_inicio  date not null,
  fecha_fin     date,
  -- Superficie quemada AUTORITATIVA (ha). NULL cuando no está confirmada (semilla
  -- sin cifra citable); EFFIS la rellena. No es ST_Area(geom) en las semillas.
  superficie_ha numeric(9, 1),
  geom          geometry(MultiPolygon, 25830) not null,
  es_semilla    boolean not null default false,
  ext_id        text, -- id del producto EFFIS/GWIS; NULL en semillas
  source        text not null,
  source_url    text,
  fetched_at    timestamptz not null default now(),
  constraint incendio_srid check (st_srid(geom) = 25830),
  constraint incendio_superficie_pos check (superficie_ha is null or superficie_ha > 0),
  constraint incendio_fechas check (fecha_fin is null or fecha_fin >= fecha_inicio)
);

-- Idempotencia EFFIS/GWIS: re-cargar el mismo producto no duplica.
create unique index incendio_ext_uk on incendio_historico (source, ext_id)
  where ext_id is not null;
-- Idempotencia semillas: un evento semilla por nombre + fecha de inicio.
create unique index incendio_semilla_uk on incendio_historico (nombre, fecha_inicio)
  where es_semilla;

create index incendio_geom_gix on incendio_historico using gist (geom);
create index incendio_fecha_ix on incendio_historico (fecha_inicio);
