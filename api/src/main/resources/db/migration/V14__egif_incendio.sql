-- V14 · Positivos del backtest (docs/09): incendios ≥100 ha de Castellón según la
-- Estadística General de Incendios Forestales (EGIF, MITECO), 2005-2022.
--
-- Es la fuente AUTORITATIVA de positivos, distinta de las semillas de
-- incendio_historico (que eran aproximadas y alimentan f_tiempo). El EGIF da UN
-- parte por incendio con su término de INICIO + la superficie total; los
-- municipios afectados (Bejís marcó 12) NO vienen enumerados, solo el nº. Por eso
-- el backtest atribuye el positivo al término de inicio y trata los vecinos con la
-- corrección de contaminación de etiquetas (docs/09): se excluyen de los negativos
-- en la ventana [fecha_inicio, fecha_fin], sin contarlos como positivos.
--
-- La superficie es la FORESTAL del EGIF (Bejís = 16.836 ha), no la cifra de prensa
-- (~19.000): es la autoritativa. Discrepancia anotada en docs/09.
create table egif_incendio (
  numeroparte    bigint primary key,
  ine_inicio     char(5) not null,          -- término de inicio (por coords, o idmunicipio)
  fecha_inicio   date not null,             -- detección
  fecha_fin      date,                       -- extinción (ventana del incendio)
  superficie_ha  numeric(9, 1) not null check (superficie_ha > 0),
  num_municipios smallint,                   -- nummunicipiosafectados del parte
  geom           geometry(Point, 25830),     -- punto de inicio (de lat/lon), si consta
  source         text not null,
  source_url     text,
  constraint egif_srid check (geom is null or st_srid(geom) = 25830),
  constraint egif_fin check (fecha_fin is null or fecha_fin >= fecha_inicio)
);

create index egif_fecha_ix on egif_incendio (fecha_inicio);
create index egif_ine_ix on egif_incendio (ine_inicio);
