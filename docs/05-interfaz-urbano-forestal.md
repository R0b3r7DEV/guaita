# 05 · Módulo de interfaz urbano-forestal (IUF)

El módulo más accionable del proyecto. Todo lo demás informa; esto genera una
lista de direcciones con las que un ayuntamiento puede trabajar mañana.

## Base normativa

El Decreto Legislativo 1/2021 (TRLOTUP), **anexo XI, Prevención de Incendios
Forestales**, establece obligaciones de autoprotección para edificaciones en
zona de influencia forestal, incluida la franja perimetral libre de vegetación
susceptible de propagar el fuego.

**Antes de generar informes con lenguaje normativo, verificar la redacción
vigente del anexo XI y del Decreto 91/2023** (reglamento de la Ley Forestal), y
citarla literalmente en el informe. La anchura exacta y las condiciones varían
según el instrumento aplicable y el tipo de implantación. El sistema debe
soportar una franja **parametrizable** (`franja_m`, por defecto 30) y registrar
qué criterio se aplicó en cada análisis.

**Redacción obligatoria en todo informe:** el análisis es una estimación
geométrica automatizada a partir de cartografía oficial y **no constituye una
certificación de cumplimiento normativo**, que corresponde al órgano competente
previa inspección.

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

| Clase | Criterio | Color |
|---|---|---|
| Crítico | `dist_forestal_m = 0` (edificio dentro de masa forestal) | rojo |
| Incumple | `dist < franja_m` | ámbar |
| Ajustado | `franja_m <= dist < franja_m * 1.5` | amarillo |
| Cumple | `dist >= franja_m * 1.5` | verde |

La clase "ajustado" existe porque la geometría catastral tiene error y una
edificación a 31 m de la masa no está realmente a salvo.

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
