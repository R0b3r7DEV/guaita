-- V15 · Perímetros de incendio de la GVA/ICV (1993-2024, CC-BY). Geometría real del
-- área quemada, con enlace al parte EGIF por `numpif` (= NumPIF_Min = numeroparte).
--
-- Es el sustituto de EFFIS (WFS caído) para las dos cosas que faltaban (docs/09):
--   * f_tiempo REAL: cruce del perímetro con municipio.geom + terreno_forestal, con
--     el umbral de reparto (>= reparto-min-frac-forestal) → qué término resetea el
--     combustible y cuándo (fecha del parte).
--   * Corrección de contaminación de etiquetas: los municipios que el perímetro
--     cubre son los AFECTADOS (Bejís marcó 12) → se excluyen de los negativos, ya no
--     por vecindad aproximada sino por el área quemada real.
create table perimetro_incendio (
  numpif    text,                          -- NumPIF_Min = numeroparte EGIF (enlace)
  anyo      int,
  fecha     date,                          -- de f_detec (dd/mm/yyyy)
  nom_mun   text,                          -- término de inicio (texto GVA)
  prov      text,
  sup_f_ha  numeric,                       -- superficie forestal del atributo GVA (contraste)
  geom      geometry(MultiPolygon, 25830) not null,
  source    text not null,
  constraint perim_srid check (st_srid(geom) = 25830)
);

create index perim_geom_gix on perimetro_incendio using gist (geom);
create index perim_numpif_ix on perimetro_incendio (numpif);
create index perim_fecha_ix on perimetro_incendio (fecha);
