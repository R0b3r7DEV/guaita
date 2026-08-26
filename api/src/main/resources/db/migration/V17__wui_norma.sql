-- V17 · Ajuste del análisis IUF a la NORMA (Anexo XI del TRLOTUP, Decreto Legislativo 1/2021).
--
-- Texto literal del Anexo XI, punto 1 (Faja perimetral de protección): faja mínima de 30 m medida
-- desde el límite exterior de la edificación; "se ampliará en función de la pendiente del terreno,
-- alcanzando, como mínimo, los 50 metros cuando la pendiente sea superior al 30 %". Por tanto la
-- franja NO es 30 m uniformes: es 30 m salvo pendiente > 30 %, donde son 50 m.
--
-- Se guarda la pendiente por edificación (muestreada del ráster gdaldem slope -p) para aplicar la
-- franja correcta por edificación.
alter table edificacion add column if not exists pendiente_pct numeric;

-- Reencuadre de clases a la NORMA: "incumple" SOLO para lo que la norma considera incumplimiento
-- (dist < franja legal). El antiguo "ajustado" (30-45 m) era una decisión de ingeniería mía por el
-- error de la geometría catastral, NO de la norma: una edificación a >= franja CUMPLE legalmente. Se
-- degrada a una ADVERTENCIA TÉCNICA (bandera) dentro de "cumple", claramente separada del
-- incumplimiento. El informe no puede sugerir incumplimiento donde legalmente se cumple.
alter table wui_edificacion drop constraint if exists wui_clase_ok;
alter table wui_edificacion
  add column if not exists advertencia_margen boolean not null default false;
alter table wui_edificacion
  add constraint wui_clase_ok check (clase in ('critico', 'incumple', 'cumple'));
