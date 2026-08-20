-- V10 · Fase 3: factores ESTÁTICOS por municipio del índice (se recalculan cuando
-- cambia la cartografía o tras un incendio, no a diario). docs/04 §2 y §3.

-- Componente estructural (parte estática; f_tiempo NO va aquí porque depende de la
-- fecha —años desde el fuego— y se aplica al calcular el índice de cada día).
create table estructural_municipio (
  ine_code             char(5) primary key references municipio(ine_code),
  frac_forestal        numeric(5,4) not null,   -- area(forestal ∩ municipio)/area(municipio)
  continuidad          numeric(5,4) not null,   -- area(mayor componente conexo)/area_forestal
  peso_modelo          numeric(4,3) not null,   -- inflamabilidad media ponderada por área (0..1)
  -- Fracción de la superficie forestal SIN dato de combustible (overlay ~94,8 %):
  -- se le aplicó peso-defecto. Es calidad del dato, como delta_altitud_m en la meteo
  -- (docs/04 §2.2, docs/06): un municipio con 40 % sin cubrir merece menos confianza.
  frac_sin_combustible numeric(5,4) not null,
  f_pendiente          numeric(4,3) not null,   -- min(1, pendiente_p90/100 * 2)  (0..1)
  version_modelo       text not null,
  calculado_en         timestamptz not null default now(),
  constraint est_fracs check (
    frac_forestal between 0 and 1 and continuidad between 0 and 1
    and frac_sin_combustible between 0 and 1),
  constraint est_pesos check (peso_modelo between 0 and 1 and f_pendiente between 0 and 1)
);

-- Componente de vulnerabilidad, versión PROVISIONAL v1.0 (sin IUF; docs/04 §3).
-- poblacion_norm es un proxy DÉBIL (gente en casco urbano, que no arde); se
-- sustituye en v2.0 con el módulo IUF de Fase 5. Columbretes excluidas del
-- espacio protegido (Reserva Natural, sin combustible ni interfaz).
create table vulnerab_municipio (
  ine_code               char(5) primary key references municipio(ine_code),
  poblacion_norm         numeric(5,4) not null,   -- 0..1
  frac_espacio_protegido numeric(5,4) not null,   -- 0..1
  comp_vulnerab          numeric(5,2) not null,   -- 0..100
  version_modelo         text not null,
  calculado_en           timestamptz not null default now(),
  constraint vul_rangos check (
    poblacion_norm between 0 and 1 and frac_espacio_protegido between 0 and 1
    and comp_vulnerab between 0 and 100)
);
