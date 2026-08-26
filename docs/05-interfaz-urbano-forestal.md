# 05 · Módulo de interfaz urbano-forestal (IUF)

El módulo más accionable del proyecto. Todo lo demás informa; esto genera una
lista de direcciones con las que un ayuntamiento puede trabajar mañana.

## Base normativa

El Decreto Legislativo 1/2021 (TRLOTUP), **Anexo XI, Prevención de incendios
forestales**, punto 1 (*Faja perimetral de protección*). **Texto literal
verificado** sobre el consolidado del BOE (id DOGV-r-2021-90283), no de memoria:

> Toda urbanización, núcleo de población, edificación o instalación destinada a
> uso residencial, industrial o terciario en terreno forestal o colindante al
> mismo, deberán integrar las infraestructuras y medidas siguientes, de acuerdo
> con el Real decreto 893/2013 […]. **1. Faja perimetral de protección.** […] se
> deberá asegurar una **faja perimetral de protección mínima de 30 metros de
> ancho, medida desde el límite exterior de la edificación**, instalación o
> conjunto de las mismas a defender. […] Dicha distancia **se ampliará en función
> de la pendiente del terreno, alcanzando, como mínimo, los 50 metros cuando la
> pendiente sea superior al 30 %**.

Consecuencias para el análisis (implementadas):

- La franja **NO es 30 m uniformes**: es **30 m, y 50 m donde la pendiente > 30 %**.
  Se aplica por edificación con la pendiente muestreada del MDT (gdaldem slope).
- La vegetación de referencia es "terreno forestal o colindante" / "combustibles
  forestales" → la capa PATFOR (`terreno_forestal`) es el proxy usado.
- `franja_m` sigue siendo **parametrizable** y se registra por análisis; el
  criterio por defecto es el del Anexo XI (30/50 según pendiente).
- Pendiente: el propio Anexo XI reduce la franja hasta un 50 % si hay muros ≥1 m
  y la amplía en industria de riesgo en viento fuerte. No modelado (filtramos a
  residencial+agrario; la reducción por muros exige inspección de campo).

**Decreto 91/2023** (Reglamento de la Ley 3/1993 Forestal, DOGV 9634, CVE
2023/7436), **art. 145 — Condiciones de seguridad en la interfaz urbano-forestal**
(texto literal verificado):

> Las urbanizaciones, los núcleos de población, las edificaciones y las
> instalaciones […] situadas en terrenos forestales y en la Zona de Influencia
> Forestal, habrán de mantener unas condiciones […]. **Para ello deberán cumplir
> con las normas establecidas en la normativa sectorial de ordenación del
> territorio, urbanismo y paisaje y de prevención de incendios forestales.**

El art. 145 **no fija anchura propia: remite a la normativa urbanística** (el
Anexo XI del TRLOTUP, ya citado). **Confirma, no cambia** el criterio 30/50 m. El
Anexo XII del reglamento ("dimensiones de las fajas auxiliares") es de las fajas
de **infraestructuras de transporte** (carreteras, pistas), no de la faja
perimetral de edificios. **Punto 0 cerrado: ambos textos verificados desde fuentes
oficiales gratuitas (BOE para el TRLOTUP, DOGV para el 91/2023).**

**Redacción obligatoria en todo informe** (descargo literal): el análisis es una
estimación geométrica automatizada a partir de cartografía oficial y **no
constituye una certificación de cumplimiento normativo**, que corresponde al
órgano competente previa inspección.

## Algoritmo

Todo en PostGIS. Por municipio, para acotar memoria.

```sql
-- Paso 1: unión de la capa forestal con buffer de trabajo
create temp table tf_local as
select st_union(geom) as geom
from terreno_forestal tf, municipio m
where m.ine_code = :ine
  and st_dwithin(tf.geom, m.geom, 2000);   -- 2 km fuera del término

-- Paso 2: por cada edificación, franja perimetral y ocupación forestal
insert into wui_edificacion (
  ref_catastral, ine_code, dist_forestal_m,
  area_forestal_en_franja_m2, frac_franja_ocupada,
  cumple, franja_m, version_analisis)
select
  e.ref_catastral,
  e.ine_code,
  round(st_distance(e.geom, t.geom)::numeric, 2),
  round(st_area(st_intersection(
          st_difference(st_buffer(e.geom, :franja), e.geom),
          t.geom))::numeric, 2),
  round((st_area(st_intersection(
          st_difference(st_buffer(e.geom, :franja), e.geom),
          t.geom))
       / nullif(st_area(st_difference(
          st_buffer(e.geom, :franja), e.geom)), 0))::numeric, 3),
  st_distance(e.geom, t.geom) >= :franja,
  :franja,
  :version
from edificacion e
cross join tf_local t
where e.ine_code = :ine
  and st_dwithin(e.geom, t.geom, :franja * 3)   -- descarta lo obviamente lejano
on conflict (ref_catastral) do update set
  dist_forestal_m = excluded.dist_forestal_m,
  area_forestal_en_franja_m2 = excluded.area_forestal_en_franja_m2,
  frac_franja_ocupada = excluded.frac_franja_ocupada,
  cumple = excluded.cumple,
  franja_m = excluded.franja_m,
  version_analisis = excluded.version_analisis,
  calculado_en = now();
```

Notas de rendimiento:
- El `ST_DWithin` con índice GiST es lo que hace esto viable. Sin él, producto
  cartesiano y el servidor se muere.
- `ST_Union` de toda la capa forestal de un municipio grande puede ser pesado.
  Si tarda, usar `ST_Subdivide` sobre la capa forestal en la carga del ETL
  (trocear polígonos gigantes en piezas de ≤ 256 vértices). Es la optimización
  estándar de PostGIS para esto y multiplica la velocidad por órdenes de
  magnitud.
- Ejecutar por lotes de municipio en transacciones separadas.

## Clasificación de salida

La clase se ciñe a la NORMA: `franja_m` es 30 m (o 50 m si pendiente > 30 %).

| Clase | Criterio | Significado |
|---|---|---|
| Crítico | `dist_forestal_m = 0` (edificio dentro de masa forestal) | incumplimiento (el peor) |
| Incumple | `0 < dist < franja_m` | **incumplimiento legal** |
| Cumple | `dist >= franja_m` | **cumple legalmente** |

**`advertencia_margen`** (bandera, NO una clase de incumplimiento): edificación
que **cumple** pero está cerca (`franja_m <= dist < franja_m · 1,5`). Es una
**cautela técnica** por el error de la geometría catastral —una edificación a
32 m en terreno llano cumple legalmente—, y se presenta claramente separada del
incumplimiento. **El informe no puede sugerir que alguien incumple cuando
legalmente cumple.**

## Salida: informe municipal

PDF generado desde el backend con:

1. Resumen: total de edificaciones analizadas, reparto por clase, superficie
   estimada de franja a desbrozar.
2. Mapa del término con las edificaciones coloreadas.
3. Tabla de referencias catastrales por clase, ordenada por distancia ascendente.
   **Solo referencia catastral y coordenada. Sin titulares. Sin direcciones
   postales nominativas.**
4. El descargo de responsabilidad literal.
5. Fecha de la cartografía de origen y versión del análisis.

## Privacidad

Esto es lo que más cuidado requiere del proyecto entero.

- Se usa exclusivamente geometría de edificio y referencia catastral, ambas
  procedentes de los servicios INSPIRE públicos del Catastro.
- **Nunca** se descargan ni almacenan datos de titularidad.
- El informe por término es descargable; el listado completo provincial de
  incumplimientos **no se expone públicamente en la API**. Un mapa público de
  "casas indefensas ante el fuego" es un problema, no un producto. Acceso al
  detalle solo autenticado.
- El agregado (número de incumplimientos por municipio) sí es público: es
  interés general y no identifica a nadie.

Documentar esta decisión en el modelo de amenazas (doc 07). Es exactamente el
tipo de razonamiento que distingue a alguien con formación en seguridad.

## Frecuencia

Recálculo cuando cambie la cartografía catastral (semestral) o la capa PATFOR.
No es un proceso diario.
