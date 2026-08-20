-- V9 · Fase 3: índice de peligro compuesto (docs/03, docs/04) y climatología FWI
-- congelada para la normalización comp_meteo.

-- Índice compuesto GUAITA (0..100), append-only. Un cálculo por (municipio, fecha).
-- comp_estructural/comp_vulnerab se guardan por fecha aunque cambien lento: el
-- estructural deriva con f_tiempo (años desde el fuego) y la cartografía.
create table indice_peligro (
  ine_code         char(5) not null references municipio(ine_code),
  fecha            date    not null,
  comp_meteo       numeric(5,2) not null,   -- 0..100 (percentil del FWI, ventana ±15d)
  comp_estructural numeric(5,2) not null,   -- 0..100, cambia lento
  comp_vulnerab    numeric(5,2) not null,   -- 0..100, casi estático
  indice           numeric(5,2) not null,   -- media geométrica (docs/04 §4)
  nivel            smallint not null,       -- 1..5
  alerta_30_30_30  boolean not null default false,  -- regla del 30 (bandera aparte)
  -- viento_alineado: NULLABLE a propósito. Requiere DIRECCIÓN de viento, que solo
  -- se captura desde Fase 3 en adelante (el histórico no la tiene y no se
  -- re-backfillea). NULL = "sin dato de dirección", NO false: un false silencioso
  -- mentiría (docs/04, docs/06). Operativo-solo.
  viento_alineado  boolean,
  version_modelo   text not null,           -- de model-params.yml; imprescindible (docs/04 §5)
  calculado_en     timestamptz not null default now(),
  primary key (ine_code, fecha),
  constraint ip_nivel check (nivel between 1 and 5),
  constraint ip_rangos check (
    comp_meteo between 0 and 100 and comp_estructural between 0 and 100
    and comp_vulnerab between 0 and 100 and indice between 0 and 100)
);

-- Climatología FWI: distribución de referencia CONGELADA por (municipio, día del
-- año), atada a version_modelo (docs/04 §1). `breakpoints` = 101 cuantiles P0..P100
-- del FWI en la ventana ±15 días sobre el periodo base. comp_meteo(hoy) interpola
-- el FWI de hoy contra estos breakpoints. El scheduler NO la actualiza; se recalcula
-- solo al subir version_modelo.
create table fwi_climatologia (
  ine_code       char(5) not null references municipio(ine_code),
  doy            smallint not null,          -- día del año 1..366
  version_modelo text not null,
  breakpoints    numeric(6,2)[] not null,    -- P0..P100 (101 valores, ascendentes)
  base_desde     date not null,
  base_hasta     date not null,
  calculado_en   timestamptz not null default now(),
  primary key (ine_code, doy, version_modelo),
  constraint clim_doy check (doy between 1 and 366),
  constraint clim_bp_len check (array_length(breakpoints, 1) = 101)
);
