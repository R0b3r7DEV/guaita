-- V16 · Interfaz urbano-forestal (IUF, docs/05). Edificaciones del Catastro INSPIRE y el resultado
-- del análisis de franja perimetral.
--
-- PRIVACIDAD (T2 de docs/07, lo más delicado de la fase): del Catastro se toma EXCLUSIVAMENTE la
-- geometría del edificio y su REFERENCIA CATASTRAL, ambas de los servicios INSPIRE públicos. NUNCA
-- titularidad, NUNCA direcciones postales nominativas. El agregado por municipio (cuántos incumplen)
-- es público (interés general, no identifica a nadie); el DETALLE por edificación es sensible y va
-- autenticado (JWT, z>=14) — un mapa público de "casas indefensas ante el fuego" es un problema, no
-- un producto. Aquí solo se define el almacén; el control de acceso vive en la API.
create table edificacion (
  ref_catastral text primary key,            -- localId INSPIRE (14 chars); NO es dato personal
  ine_code      char(5) not null references municipio(ine_code),
  uso           text,                         -- currentUse INSPIRE (1_residential, 2_agriculture…)
  geom          geometry(MultiPolygon, 25830) not null,
  source        text not null,
  fetched_at    timestamptz not null default now(),
  source_url    text not null,
  constraint edif_srid check (st_srid(geom) = 25830)
);
create index edif_geom_gix on edificacion using gist (geom);
create index edif_ine_ix on edificacion (ine_code);

-- Resultado del análisis de franja perimetral por edificación (docs/05). `clase` deriva de la
-- distancia al bosque; `franja_m` y `version_analisis` registran QUÉ criterio se aplicó (la anchura
-- normativa es parametrizable y varía según el instrumento — anexo XI TRLOTUP / Decreto 91/2023).
create table wui_edificacion (
  ref_catastral              text primary key references edificacion (ref_catastral) on delete cascade,
  ine_code                   char(5) not null references municipio(ine_code),
  dist_forestal_m            numeric,        -- NULL si no hay bosque en el radio de búsqueda (lejos)
  area_forestal_en_franja_m2 numeric,
  frac_franja_ocupada        numeric,
  clase                      text not null,  -- critico | incumple | ajustado | cumple
  cumple                     boolean not null,
  franja_m                   numeric not null,
  version_analisis           text not null,
  calculado_en               timestamptz not null default now(),
  constraint wui_clase_ok check (clase in ('critico', 'incumple', 'ajustado', 'cumple'))
);
create index wui_ine_ix on wui_edificacion (ine_code);
create index wui_ine_clase_ix on wui_edificacion (ine_code, clase);
