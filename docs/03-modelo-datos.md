# 03 · Modelo de datos

SRID de trabajo: **25830**. Toda columna `geometry` lleva
`CHECK (ST_SRID(geom) = 25830)` e índice GiST.

## Dimensiones (estáticas)

```sql
-- Unidad de análisis. 135 municipios de Castellón.
create table municipio (
  ine_code        char(5) primary key,          -- código INE
  nombre          text        not null,
  nombre_va       text,
  comarca         text        not null,
  geom            geometry(MultiPolygon, 25830) not null,
  superficie_ha   numeric(10,2) not null,
  poblacion       integer,
  constraint municipio_srid check (st_srid(geom) = 25830)
);
create index municipio_geom_gix on municipio using gist (geom);

-- Terreno forestal PATFOR, recortado a la provincia.
create table terreno_forestal (
  id              bigserial primary key,
  modelo_combustible text,                      -- según esquema PATFOR, ver ETL
  modelo_norm     smallint,                     -- normalizado 1..7 (Prometheus)
  geom            geometry(MultiPolygon, 25830) not null,
  source          text not null,
  fetched_at      timestamptz not null default now(),
  constraint tf_srid check (st_srid(geom) = 25830)
);
create index tf_geom_gix on terreno_forestal using gist (geom);

-- Edificaciones Catastro (solo geometría + refcat, sin titulares).
create table edificacion (
  ref_catastral   varchar(20) primary key,
  ine_code        char(5) references municipio(ine_code),
  uso             text,
  geom            geometry(MultiPolygon, 25830) not null,
  constraint ed_srid check (st_srid(geom) = 25830)
);
create index ed_geom_gix on edificacion using gist (geom);
create index ed_muni_ix  on edificacion (ine_code);

-- Métricas topográficas precalculadas por municipio (gdaldem + zonal stats).
create table topografia_municipio (
  ine_code            char(5) primary key references municipio(ine_code),
  pendiente_media_pct numeric(5,2),
  pendiente_p90_pct   numeric(5,2),
  frac_solana         numeric(4,3),   -- fracción con orientación S/SE/SO
  altitud_media_m     numeric(7,2)
);
```

## Hechos meteorológicos

```sql
create table estacion_meteo (
  codigo   text primary key,          -- indicativo AEMET
  nombre   text not null,
  geom     geometry(Point, 25830) not null,
  altitud_m numeric(7,2),
  constraint est_srid check (st_srid(geom) = 25830)
);

create table observacion_meteo (
  estacion_codigo text        not null references estacion_meteo(codigo),
  fecha           date        not null,
  temp_max_c      numeric(5,2),
  temp_12utc_c    numeric(5,2),      -- referencia para FWI
  hr_12utc_pct    numeric(5,2),
  viento_12utc_kmh numeric(5,2),
  precip_24h_mm   numeric(6,2),
  source          text not null,
  fetched_at      timestamptz not null default now(),
  primary key (estacion_codigo, fecha)
);

-- Meteo YA asignada a cada municipio (tras interpolación IDW + corrección
-- altitudinal, ver doc 04). Es la ENTRADA del FWI, separada del cálculo.
-- Separarla permite: (a) cambiar de fuente AEMET<->Open-Meteo sin tocar el
-- FWI, (b) reproducir el backtest sobre la misma entrada meteo exacta, y
-- (c) depurar por separado el fallo de interpolación del fallo de la fórmula.
-- La calidad del dato (interpolado / n_estaciones) es propiedad de la meteo
-- asignada, NO del FWI; el endpoint del doc 06 sirve calidadDato desde aquí.
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
  fetched_at       timestamptz not null default now(),
  primary key (ine_code, fecha)
);
```

## Índices calculados

```sql
-- Códigos FWI. Recursivos: cada día depende del anterior. Ver doc 04.
-- Consume la entrada meteorológica de meteo_municipio por (ine_code, fecha).
create table fwi_municipio (
  ine_code   char(5) not null references municipio(ine_code),
  fecha      date    not null,
  ffmc       numeric(6,2) not null,
  dmc        numeric(7,2) not null,
  dc         numeric(8,2) not null,
  isi        numeric(6,2) not null,
  bui        numeric(7,2) not null,
  fwi        numeric(7,2) not null,
  calculado_en timestamptz not null default now(),
  primary key (ine_code, fecha)
);

-- Índice compuesto GUAITA (0..100).
create table indice_peligro (
  ine_code        char(5) not null references municipio(ine_code),
  fecha           date    not null,
  comp_meteo      numeric(5,2) not null,   -- 0..100 derivado de FWI
  comp_estructural numeric(5,2) not null,  -- 0..100, cambia lento
  comp_vulnerab   numeric(5,2) not null,   -- 0..100, casi estático
  indice          numeric(5,2) not null,   -- combinación, ver doc 04
  nivel           smallint not null,       -- 1..5
  alerta_30_30_30 boolean not null default false, -- regla del 30, bandera aparte (doc 04)
  viento_alineado boolean not null default false, -- viento ±45° con la ladera dominante
  version_modelo  text not null,           -- 'v1.0' — imprescindible
  primary key (ine_code, fecha)
);
```

`version_modelo` no es opcional. Cuando se recalibre el índice hay que poder
distinguir qué versión produjo cada valor histórico, o el backtest deja de
significar nada.

## Focos e incendios

```sql
create table foco_termico (
  id           bigserial primary key,
  sensor       text not null,             -- VIIRS_SNPP, VIIRS_NOAA20, MODIS
  acq_ts       timestamptz not null,
  geom         geometry(Point, 25830) not null,
  brightness_k numeric(7,2),
  frp_mw       numeric(9,2),
  confidence   text,                      -- low|nominal|high
  daynight     char(1),
  cluster_id   bigint,                    -- ST_ClusterDBSCAN
  ine_code     char(5) references municipio(ine_code),
  source       text not null,
  fetched_at   timestamptz not null default now(),
  unique (sensor, acq_ts, geom)           -- idempotencia
);
create index foco_geom_gix on foco_termico using gist (geom);
create index foco_ts_ix    on foco_termico (acq_ts desc);

-- Eventos históricos conocidos, cargados a mano + EFFIS. Verdad de terreno.
create table incendio_historico (
  id             bigserial primary key,
  nombre         text not null,            -- 'Bejís 2022'
  fecha_inicio   date not null,
  fecha_control  date,
  superficie_ha  numeric(10,2),
  geom           geometry(MultiPolygon, 25830),
  causa          text,
  fuente         text not null
);
```

Semilla obligatoria de `incendio_historico` (es el conjunto de test del backtest):

| nombre | fecha_inicio | superficie_ha | causa |
|---|---|---|---|
| Bejís | 2022-08-15 | ~19.000 | rayo |
| Useres–Costur–Figueroles–Llucena | 2022-08-15 | 800 | — |
| Villanueva de Viver | 2023-03-23 | 4.700 | — |
| Serra d'Espadà (Vall d'Uixó) | 2026-07-25 | por determinar | en investigación |

Rellenar con el histórico largo desde EFFIS antes de dar el backtest por válido:
4 eventos son pocos. Objetivo mínimo: 20 años de área quemada de la provincia.

## Interfaz urbano-forestal

```sql
create table wui_edificacion (
  ref_catastral   varchar(20) primary key references edificacion(ref_catastral),
  ine_code        char(5) not null,
  dist_forestal_m numeric(8,2) not null,   -- 0 si está dentro
  area_forestal_en_franja_m2 numeric(10,2) not null,
  frac_franja_ocupada numeric(4,3) not null,
  cumple          boolean not null,
  franja_m        smallint not null,       -- 25 o 30, según criterio aplicado
  calculado_en    timestamptz not null default now(),
  version_analisis text not null
);
create index wui_muni_ix on wui_edificacion (ine_code, cumple);
```

## Alertas

```sql
create table suscripcion (
  id          uuid primary key default gen_random_uuid(),
  email       citext not null,
  ine_code    char(5) not null references municipio(ine_code),
  umbral      smallint not null default 4,   -- nivel a partir del cual avisar
  verificada  boolean not null default false,
  token_verif text,
  creada_en   timestamptz not null default now(),
  unique (email, ine_code)
);

create table envio_alerta (
  id          bigserial primary key,
  suscripcion_id uuid not null references suscripcion(id) on delete cascade,
  fecha       date not null,
  nivel       smallint not null,
  enviada_en  timestamptz not null default now(),
  unique (suscripcion_id, fecha)            -- máximo un aviso por día
);
```

Doble opt-in obligatorio y baja en un clic. Es RGPD, no una preferencia.

## Notas de implementación

- Particionar `foco_termico` por año si el histórico crece: son millones de filas.
- `indice_peligro` y `fwi_municipio` son append-only. No hay `UPDATE` fuera del
  recálculo explícito de una versión de modelo.
- Vista materializada `mv_indice_hoy` con el último índice por municipio,
  refrescada al final del job diario. Es lo que consume el endpoint de tiles.
