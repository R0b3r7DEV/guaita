-- V6 · Topografía por municipio: estadísticos zonales del MDT25 (25 m) sobre
-- municipio.geom. Los calcula el ETL (load_topografia.sh) a partir del mosaico
-- de las 22 hojas MTN25 (Release data/mdt25-v1). Aquí solo el esquema.
--
-- LIMITACIÓN DE RESOLUCIÓN (importante, ver docs/03): a 25 m el MDE suaviza el
-- relieve, así que la pendiente calculada es SISTEMÁTICAMENTE MENOR que la real.
-- Los valores sirven para COMPARAR municipios entre sí (que es el uso en el
-- índice, doc 04), pero NO son comparables con valores de literatura calculados
-- a otra resolución. pendiente_p90_pct usa el percentil 90 a propósito: en un
-- término mandan los barrancos, no el promedio.
create table topografia_municipio (
  ine_code            char(5) primary key references municipio(ine_code),
  pendiente_media_pct numeric(5,2) not null,   -- pendiente media (%)
  pendiente_p90_pct   numeric(5,2) not null,   -- percentil 90 (%); mandan los barrancos
  frac_solana         numeric(4,3) not null,   -- fracción con orientación S/SE/SO (solana)
  altitud_media_m     numeric(7,2) not null,
  constraint topo_p90_ge_media check (pendiente_p90_pct >= pendiente_media_pct)
);
