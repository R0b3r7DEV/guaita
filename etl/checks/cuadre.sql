-- Consultas de cuadre de la carga de municipios (Fase 1).
-- Se ejecutan tras `make seed`. Sirven de verificación a ojo contra las
-- magnitudes conocidas: Castellón ~663.200 ha y ~627.000 habitantes.
\pset pager off

\echo == Q1 - superficie total (ha) ==
SELECT round(sum(superficie_ha)) AS ha_total FROM municipio;

\echo == Q2 - poblacion total ==
SELECT sum(poblacion) AS hab_total FROM municipio;

\echo == Q3 - comarcas (comarca | count | superficie ha) ==
SELECT comarca, count(*), round(sum(superficie_ha)) AS ha
FROM municipio
GROUP BY 1
ORDER BY 2 DESC;

\echo == Q4 - municipios de control ==
SELECT ine_code, nombre, comarca, poblacion, round(superficie_ha) AS ha
FROM municipio
WHERE nombre ILIKE ANY (ARRAY['%Alcora%', '%Morella%', '%Nules%', '%Segorbe%'])
ORDER BY ine_code;
