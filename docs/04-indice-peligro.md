# 04 · Índice de peligro

**Este documento es especificación cerrada. No improvisar la fórmula.**

El índice compuesto tiene tres componentes con dinámicas muy distintas:

| Componente | Qué mide | Cambia |
|---|---|---|
| Meteorológico | Cuán inflamable está hoy el combustible | A diario |
| Estructural | Cuánto combustible hay y cómo está dispuesto | Cada meses/años |
| Vulnerabilidad | Qué se pierde si arde | Casi estático |

Mezclarlos mal es el error clásico: un índice que sube y baja con el viento pero
ignora que debajo hay 20.000 hectáreas continuas, o al revés.

---

## 1. Componente meteorológico — FWI canadiense

### Por qué FWI

Es el sistema con más rodaje mundial, está publicado y revisado por pares, se usa
en EFFIS para toda Europa (lo que permite contrastar), y sus subíndices tienen
significado físico separable. Una heurística casera no es defendible ante nadie.

### Referencia normativa de implementación

Van Wagner, C.E. (1987). *Development and Structure of the Canadian Forest Fire
Weather Index System.* Forestry Technical Report 35, Canadian Forestry Service.

**Implementar desde las ecuaciones publicadas de ese informe.** No reescribir de
memoria ni de un blog. Hay implementaciones de referencia en R (`cffdrs`) y
Python (`pyfwi`, `NASA-fwi`) que sirven para **generar vectores de test**, no
para copiar.

### Entradas (diarias, al mediodía solar ≈ 12:00 UTC en península)

| Variable | Unidad | Origen |
|---|---|---|
| Temperatura | °C | AEMET obs / Open-Meteo |
| Humedad relativa | % | ídem |
| Velocidad de viento | km/h | ídem |
| Precipitación acumulada 24 h | mm | ídem |

### Estructura

```
        temp, HR, viento, lluvia
                  │
     ┌────────────┼────────────┐
     ▼            ▼            ▼
   FFMC          DMC           DC
 (hojarasca)  (capa media)  (capa profunda)
  memoria      memoria       memoria
   ~2/3 días    ~12 días      ~52 días
     │            └─────┬──────┘
     │                  ▼
     │                 BUI  (combustible disponible)
     ▼                  │
    ISI ────────────────┘
 (propagación)          │
     └────────┬─────────┘
              ▼
             FWI
```

Puntos que hay que respetar sí o sí:

- **Los tres códigos de humedad son recursivos.** El valor de hoy depende del de
  ayer. Hay que persistir el estado (tabla `fwi_municipio`) y no se puede
  calcular un día suelto sin cadena previa.
- **Valores de arranque** (`startup values`) estándar: FFMC 85, DMC 6, DC 15.
  Aplicar tras un periodo de lluvias. Para el histórico, arrancar el 1 de marzo
  de cada año y **descartar los primeros 30 días** como calentamiento del modelo.
  Si el backtest incluye esos días, los resultados son basura.
- **Longitud de día.** DMC y DC llevan factores dependientes del mes pensados
  para latitudes canadienses. Para Castellón (≈40°N) usar los ajustes de latitud
  documentados en la literatura de adaptación mediterránea. Documentar la
  elección en el código con un comentario que cite la fuente.
- **Unidades.** Viento en km/h, no m/s. Es un error frecuente y silencioso: el
  FWI sale bajo y nadie se da cuenta.

### Tests obligatorios

`FwiCalculatorTest` con al menos:
1. Vectores de la publicación original (tabla de ejemplo de Van Wagner).
2. Un año completo de una estación real contrastado contra `cffdrs` en R.
3. Test de propiedad: con lluvia > 30 mm/día sostenida, FFMC converge al mínimo.
4. Test de regresión de la recursión: recalcular un rango debe dar lo mismo que
   calcularlo día a día.

### Asignación meteorológica a municipios

No todos los municipios tienen estación. Estrategia por orden:

1. Si hay estación dentro del término → usarla.
2. Si no → **IDW (ponderación por inverso de la distancia)** con las 3 estaciones
   más cercanas, con **corrección altitudinal de temperatura** aplicando un
   gradiente de −0,65 °C / 100 m sobre la diferencia de altitud entre la estación
   y la altitud media del municipio.
3. Persistir la meteo asignada y su calidad en `meteo_municipio` (`interpolado`
   sí/no y `n_estaciones` usadas). Un dato interpolado en Vistabella desde
   estaciones de la costa es mucho menos fiable y hay que poder saberlo.
   `fwi_municipio` consume esta tabla; la calidad es propiedad de la meteo, no
   del FWI (ver doc 03).

La corrección altitudinal importa de verdad aquí: hay 1.800 m de desnivel entre
Penyagolosa y la Plana.

### Normalización a 0..100

Percentiles sobre la serie histórica **local** de cada municipio, no umbrales
absolutos. Un FWI de 30 en el Maestrat húmedo no significa lo mismo que en el
Palancia.

```
comp_meteo = percentil(fwi_hoy, distribución histórica FWI del municipio) * 100
```

Requiere ≥ 10 años de reanálisis para que los percentiles sean estables. Es la
razón por la que el histórico de Open-Meteo entra en el alcance.

---

## 2. Componente estructural

Cuatro factores, calculados por municipio, recalculados cuando cambia la
cartografía o tras un incendio.

### 2.1 Fracción y continuidad de combustible

```sql
frac_forestal = area(terreno_forestal ∩ municipio) / area(municipio)
```

Pero la fracción sola engaña: 60 % forestal en mosaico con cultivos arde muy
distinto que 60 % en una única mancha. Medir **continuidad**:

```sql
-- Fracción de la superficie forestal que pertenece al mayor polígono conexo
continuidad = area(mayor_componente_conexo) / area_forestal_total
```

Con `ST_Union` + `ST_Dump` sobre la capa forestal recortada al término (más un
buffer de 2 km fuera del término: el fuego no respeta líneas jurisdiccionales).

### 2.2 Modelo de combustible

> **⚠️ RIESGO ABIERTO (resolver antes de Fase 3).** La capa `SF.Forestal` del
> PATFOR —la que carga la Fase 1 en `terreno_forestal`— **no contiene modelos de
> combustible**: es solo extensión forestal. La tabla de pesos de abajo asume el
> esquema **Prometheus (7 modelos)**.
>
> La investigación del WFS del PATFOR (Fase 1) encontró que **el modelo de
> combustible sí existe como dato público**, pero en otra capa:
> `ms:Regulacion.Incendios.Combustible` = «Modelo de combustible (clas.
> **Rothermel**)». Es decir: existe, pero es **Rothermel (13 modelos), NO
> Prometheus (7)**. **Esta tabla no corresponde al dato real** y hay que rehacer
> `peso_modelo_ponderado` para los 13 modelos de Rothermel antes de que la
> Fase 3 calcule `comp_estructural`.
>
> Vías de sustitución si la capa Rothermel no sirviera (solo apuntadas, sin
> evaluar aquí):
> - Derivar una aproximación de estructura de vegetación desde **SIOSE** o
>   **Corine Land Cover** (públicos y bien documentados).
> - Prescindir del factor y compensar con más peso en `continuidad` y
>   `f_tiempo`, asumiendo la pérdida de precisión.

Mapear el esquema del PATFOR a un peso de inflamabilidad 0..1. Si es Prometheus
(7 modelos), orientación:

| Modelo | Descripción | Peso |
|---|---|---|
| 1 | Pastizal | 0,55 |
| 2 | Matorral bajo (< 0,6 m) | 0,70 |
| 3 | Matorral medio (0,6–2 m) | 0,90 |
| 4 | Matorral alto / arbolado con sotobosque continuo | 1,00 |
| 5 | Arbolado con sotobosque bajo | 0,65 |
| 6 | Arbolado con sotobosque medio | 0,80 |
| 7 | Arbolado limpio, sin continuidad vertical | 0,35 |

**Verificar el esquema real del PATFOR antes de usar esta tabla.** Si es otro
(Rothermel 13, Scott & Burgan 40), rehacer el mapeo y documentarlo.

El modelo 4 es el peligroso: continuidad vertical del suelo a la copa. Es la
"escalera de combustible", y es la diferencia entre un fuego de superficie que se
apaga y un fuego de copas que no.

### 2.3 Pendiente

La velocidad de propagación crece de forma marcadamente no lineal con la
pendiente cuesta arriba — aproximadamente se duplica cada 10° adicionales en el
rango operativo. Usar `pendiente_p90_pct` (percentil 90), no la media: lo que
manda son los barrancos, no el promedio del término.

```
f_pendiente = min(1.0, (pendiente_p90_pct / 100) * 2.0)
```

### 2.4 Tiempo desde el último incendio — la curva en U

Contraintuitivo y **esencial**:

- **0–3 años tras el fuego**: peligro bajo. No hay combustible.
- **4–15 años**: peligro **máximo**. El matorral regenerado es denso, fino,
  continuo y con mucho muerto en pie. Arde peor que lo que había antes.
- **> 25 años**: alto de nuevo, por acumulación, pero con estructura arbolada más
  lenta.

Esto no es teórico: el trabajo técnico del Mapa de Peligro de la CV (2024)
advierte que las zonas quemadas pocos años antes aparecen en la cartografía de
modelos de combustible con estructuras que ya se encuentran en estadios mucho más
inflamables. **Bejís 2022 y Villanueva de Viver 2023 están entrando ahora mismo
en su ventana de máximo peligro.** Que el índice capture eso es la aportación
diferencial del proyecto.

```
f_tiempo(t) = 0.15                         si t <= 3
            = 0.15 + 0.85*(t-3)/5          si 3 < t <= 8      → sube rápido
            = 1.00                         si 8 < t <= 15
            = 1.00 - 0.30*(t-15)/10        si 15 < t <= 25
            = 0.70                         si t > 25
```

Curva propuesta, **calibrable en la fase de validación**. Dejarla en un fichero
de configuración, no incrustada en el código.

### Combinación estructural

```
comp_estructural = 100 * frac_forestal^0.5
                       * (0.5 + 0.5*continuidad)
                       * peso_modelo_ponderado
                       * (0.6 + 0.4*f_pendiente)
                       * f_tiempo
```

La raíz de `frac_forestal` evita que un municipio con 30 % forestal quede
descartado: 30 % de un término grande sigue siendo mucho monte.

---

## 3. Componente de vulnerabilidad

```
comp_vulnerab = 100 * norm( 0.45 * n_edificaciones_en_interfaz
                          + 0.30 * poblacion_en_franja_500m
                          + 0.15 * (1 / n_vias_evacuacion)
                          + 0.10 * frac_espacio_protegido )
```

`n_edificaciones_en_interfaz` sale directamente del módulo WUI (doc 05).
`n_vias_evacuacion` = número de carreteras que cruzan el límite municipal; un
pueblo con una sola salida es un problema operativo grave y hay que reflejarlo.

Este componente es el que hace que el Desert de les Palmes puntúe alto pese a
tener poca superficie forestal: masa pequeña, población enorme al lado.

---

## 4. Índice compuesto

```
indice = comp_meteo^0.5  *  (0.65*comp_estructural + 0.35*comp_vulnerab)^0.5
```

**Media geométrica, no aritmética.** Es deliberado: si el componente
meteorológico es cero (día lluvioso), el índice debe irse a cero por mucho
combustible que haya. Una suma ponderada no hace eso y da falsos positivos todo
el invierno.

### Niveles

| Nivel | Rango | Etiqueta | Lectura operativa |
|---|---|---|---|
| 1 | 0–20 | Bajo | — |
| 2 | 21–40 | Moderado | Vigilancia normal |
| 3 | 41–60 | Alto | Restringir trabajos con maquinaria |
| 4 | 61–80 | Muy alto | Preposicionar medios |
| 5 | 81–100 | Extremo | Prohibición total de fuego, vigilancia máxima |

### Regla de alineación (bandera aparte, no parte del índice)

Regla operativa clásica de los servicios de extinción, conocida como **"regla del
30"**: cuando coinciden temperatura > 30 °C, humedad relativa < 30 % y viento
> 30 km/h, el comportamiento del fuego pasa a ser extremo y probablemente fuera
de capacidad de extinción.

Añadir un flag booleano `alerta_30_30_30` en `indice_peligro`, calculado aparte y
mostrado en la UI como aviso destacado. **No promediarlo dentro del índice**: su
valor está justamente en que es una condición binaria, reconocible y accionable.

Añadir también `viento_alineado_pendiente`: cuando la dirección del viento
coincide (±45°) con la orientación de las laderas dominantes del municipio, la
propagación se dispara. Es exactamente lo que pasó en la Vall d'Uixó con la
ponentà del 25 de julio de 2026.

---

## 5. Versionado

Cada cálculo escribe `version_modelo`. Cambiar cualquier constante de este
documento implica subir versión y **recalcular todo el histórico** antes de
volver a lanzar el backtest. Comparar métricas entre versiones distintas no
significa nada.
