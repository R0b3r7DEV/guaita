-- V7 · Fase 2: meteo asignada por municipio (entrada del FWI) e índices FWI.
-- (La migración de esta fase NO puede ser "V4": V4 ya es terreno_forestal; va como V7.)
--
-- meteo_municipio: la meteo YA asignada a cada término (tras IDW + corrección
-- altitudinal, doc 04). Es la ENTRADA del FWI, SEPARADA del cálculo, para poder
-- (a) cambiar AEMET<->Open-Meteo sin tocar el FWI, (b) reproducir el backtest
-- sobre la misma entrada exacta, (c) depurar interpolación y fórmula por separado.
-- La calidad del dato (interpolado / n_estaciones) es propiedad de la meteo, no
-- del FWI (doc 03/06). Append-only. Se añade source_url (regla de procedencia,
-- doc 02) que el DDL de doc 03 abreviaba.
create table meteo_municipio (
  ine_code         char(5) not null references municipio(ine_code),
  fecha            date    not null,
  temp_12utc_c     numeric(5,2) not null,
  hr_12utc_pct     numeric(5,2) not null,
  viento_12utc_kmh numeric(5,2) not null,
  precip_24h_mm    numeric(6,2) not null,
  interpolado      boolean  not null,
  n_estaciones     smallint not null,
  source           text     not null,
  source_url       text,
  fetched_at       timestamptz not null default now(),
  primary key (ine_code, fecha)   -- índice por (ine_code, fecha); target de la FK del FWI
);

-- Códigos FWI, RECURSIVOS: cada día depende del anterior (doc 04). Append-only.
-- Consumen meteo_municipio por (ine_code, fecha); la FK compuesta impide calcular
-- un FWI sin su entrada meteo (sería un error silencioso).
--   calentamiento: tras los valores de arranque (FFMC 85, DMC 6, DC 15) el modelo
--   necesita ~30 días para converger. Esos primeros días de cada temporada se
--   marcan para poder EXCLUIRLOS del backtest (doc 04 §1). No se deja implícito.
create table fwi_municipio (
  ine_code      char(5) not null references municipio(ine_code),
  fecha         date    not null,
  ffmc          numeric(6,2) not null,
  dmc           numeric(7,2) not null,
  dc            numeric(8,2) not null,
  isi           numeric(6,2) not null,
  bui           numeric(7,2) not null,
  fwi           numeric(7,2) not null,
  calentamiento boolean not null default false,
  calculado_en  timestamptz not null default now(),
  primary key (ine_code, fecha),   -- índice por (ine_code, fecha)
  constraint fwi_meteo_fk foreign key (ine_code, fecha)
    references meteo_municipio (ine_code, fecha),
  constraint fwi_no_neg check (
    ffmc >= 0 and dmc >= 0 and dc >= 0 and isi >= 0 and bui >= 0 and fwi >= 0)
);
