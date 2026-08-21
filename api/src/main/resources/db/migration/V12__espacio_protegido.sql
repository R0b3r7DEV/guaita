-- V12 · Espacios protegidos (Red Natura 2000 + ENP) para comp_vulnerab
-- provisional (docs/04 §3). Fuente: Infraestructura Verde del ICV/GVA (mismo
-- geoportal que el PATFOR). Recortados al continente en la carga: la Reserva
-- Natural de las Columbretes (marina, sin combustible ni interfaz) queda fuera,
-- como exige docs/04 §3.
--
-- frac_espacio_protegido se calcula por municipio en el servicio (unión de los
-- solapes para no duplicar donde RN2000 y ENP coinciden). Aquí solo viven las
-- geometrías con su procedencia.
create table espacio_protegido (
  id         bigint generated always as identity primary key,
  categoria  text not null, -- 'RN2000' (ZEC/ZEPA) | 'ENP' (parques, reservas…)
  nombre     text,
  geom       geometry(MultiPolygon, 25830) not null,
  source     text not null,
  source_url text,
  fetched_at timestamptz not null default now(),
  constraint ep_srid check (st_srid(geom) = 25830),
  constraint ep_categoria check (categoria in ('RN2000', 'ENP'))
);

create index ep_geom_gix on espacio_protegido using gist (geom);
