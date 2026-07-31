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

### Entradas (diarias, a las **12:00 UTC** — criterio EFFIS)

| Variable | Unidad | Origen |
|---|---|---|
| Temperatura | °C | Open-Meteo / AEMET obs |
| Humedad relativa | % | ídem |
| Velocidad de viento | km/h | ídem |
| Precipitación acumulada 24 h | mm | ídem |

> **Definición de "mediodía" — FIJADA en 12:00 UTC.** El FWI se define sobre
> observaciones de mediodía. EFFIS, para toda Europa, seleccionó **las 12:00 UTC**
> como entrada del modelo ("after several tests the 12 UTC model output was
> considered the most suitable"; *Fire Danger Forecast*, EFFIS/Copernicus). Se
> adopta ese criterio por **consistencia y comparabilidad externa con EFFIS**, no
> el mediodía solar local ni las 12:00 de reloj (que serían 11:00 UTC en invierno
> y 10:00 UTC en verano y desplazarían sistemáticamente temperatura y HR, justo lo
> que come el FFMC). Para Castellón, cerca del meridiano 0, las 12:00 UTC coinciden
> además con el mediodía solar. **La conversión horaria vive en UN solo sitio del
> código.** Ojo con Open-Meteo: su API horaria devuelve UTC por defecto (timezone
> GMT); se indexa la hora 12 en UTC sin pasar `timezone`.

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
- **Arranque y calentamiento — CADENA CONTINUA todo el año (criterio EFFIS).**
  Valores de arranque estándar FFMC 85, DMC 6, DC 15, aplicados **UNA sola vez**
  al inicio de toda la serie histórica (1-ene-2005), NO cada temporada. La cadena
  recursiva corre **todos los días del año sin reinicio**, igual que EFFIS, que
  opera el FWI durante todo el año (núcleo de campaña 1-mar a 31-oct). Solo los
  **~30 primeros días de la serie completa** (ene-2005) se marcan como
  calentamiento (`calentamiento=true` en `fwi_municipio`) y se excluyen del
  backtest; el resto de la serie es válido.
  > **Por qué NO reiniciar por temporada (con fuente).** El criterio canadiense de
  > reiniciar cada primavera y descartar 30 días **excluiría por construcción a
  > Villanueva de Viver (23-mar-2023, 4.700 ha)**, uno de los cuatro eventos
  > semilla del backtest (docs/03); y que un gran incendio ocurra en marzo
  > demuestra que la temporada mediterránea no arranca en la fecha canadiense. La
  > cadena continua lo incluye. No hace falta el procedimiento formal de
  > *overwintering* del DC (Van Wagner 1987): al no reiniciar, el DC se arrastra
  > solo por el invierno, y en el interior de Castellón la precipitación invernal
  > recarga la capa profunda (Lawson & Armitage 2008: el overwintering es
  > innecesario donde la precipitación de invierno supera ~200 mm). Fuentes:
  > EFFIS/Copernicus *Fire Danger Forecast*; GEFF, Vitolo et al. 2020 (ESSD
  > 12:1823), reanálisis continuo de fire weather.
- **Longitud de día.** DMC y DC llevan factores dependientes del mes (Tablas 1 y
  2 de Van Wagner & Pickett 1985) tabulados para latitudes canadienses (≈46°N).
  Para otras latitudes, Lawson & Armitage (2008) proponen factores ajustados por
  bandas.
  > **✅ RESUELTO (verificado, no asumido).** El ajuste de Lawson & Armitage 2008
  > tal como lo implementa `cffdrs` usa **exactamente los valores canadienses
  > estándar por encima de 30°N (DMC) y de 20°N (DC)**; solo cambia en bandas
  > tropicales/australes. Castellón (~40°N) cae de lleno en la banda estándar,
  > así que los factores por defecto del `FwiCalculator` **ya son los correctos**
  > para esta latitud. Fuente: `cffdrs` R, `dmcCalc.R` (`ell01`, `lat > 30`) y
  > `dcCalc.R` (`fl01`, `lat > 20`), que implementan Lawson & Armitage 2008,
  > "Weather Guide for the Canadian Forest Fire Danger Rating System". Le/Lf
  > quedan configurables por si el sistema se llevara a otra latitud, pero para
  > Castellón no hay ajuste pendiente.
- **Unidades.** Viento en km/h, no m/s. Es un error frecuente y silencioso: el
  FWI sale bajo y nadie se da cuenta.

### Tests obligatorios

`FwiCalculatorTest` con al menos:
1. Vectores de la publicación original (tabla de ejemplo de Van Wagner).
2. Un año completo de una estación real contrastado contra `cffdrs` en R.
3. Test de propiedad: con lluvia > 30 mm/día sostenida, FFMC converge al mínimo.
4. Test de regresión de la recursión: recalcular un rango debe dar lo mismo que
   calcularlo día a día.

> **Estado (Fase 2).** Hechos: (1) reproduce la tabla de ejemplo de Van Wagner &
> Pickett 1985 —49 días, Programa F-32— con `max|diff| < 0,1` en los seis códigos;
> (3) lluvia sostenida hunde el FFMC; (4) reanudar desde estado persistido ==
> continuo; más el rechazo de entradas imposibles.
> **COBERTURA PENDIENTE:** el test (2), contraste contra `cffdrs` en R, **no está
> hecho** (el entorno de desarrollo no tiene R). La tabla de la publicación es la
> referencia primaria, pero NO sustituye a `cffdrs`: 49 días de una estación no
> ejercitan las **fronteras de mes en Le/Lf** (solo se cruza abril→mayo; jun-dic
> sin probar), el **DC en sequía prolongada** (aquí no pasa de ~125) ni los
> **valores extremos**. Se marca como pendiente, no como cubierto. (La
> reimplementación en Python usada para inspeccionar no es validación
> independiente: mismo lector, mismas ecuaciones.)

### Asignación meteorológica a municipios (fuente en rejilla, ADR-07)

La fuente es un reanálisis en rejilla (Open-Meteo ERA5-Seamless), no estaciones.
La asignación por municipio:

1. **Punto**: `ST_PointOnSurface` del continente del término (docs/03), en 4326.
2. **Downscaling altitudinal — lo hace Open-Meteo, no nosotros.** Se pide con
   `elevation = altitud_media_m` (de `topografia_municipio`) y Open-Meteo
   downscalea temperatura **y humedad** a esa altitud con su lapse rate estándar
   (**−0,65 °C/100 m, verificado empíricamente**). Aplicar nuestra corrección
   encima sería **doble contabilidad**; recalcular la HR con Magnus sería pelear
   contra un par (T, HR) que Open-Meteo ya devuelve **termodinámicamente
   consistente** (al bajar T mantiene la HR y baja el punto de rocío).
3. **Calidad del dato** (`meteo_municipio`, docs/03): con rejilla no hay
   estaciones; lo que importa es cuánto tuvo que downscalear el modelo, es decir
   la discrepancia de altitud entre su celda nativa y el municipio:
   `elevacion_celda_m` (petición de referencia sin `elevation`) y
   `delta_altitud_m = altitud_media_m − elevacion_celda_m`. Vistabella (delta
   grande) es menos fiable que Nules (delta ≈ 0). El endpoint del doc 06 lo sirve
   como `calidadDato`.

> **⚠️ Limitación conocida (ADR-07).** El **viento y la precipitación NO se
> downscalean** por altitud (Open-Meteo solo lo hace con temperatura/humedad; el
> viento viene de ERA5 a ~25 km). El viento en cumbre es bastante mayor que el de
> la celda promediada, así que el **ISI queda sistemáticamente subestimado en los
> municipios de montaña**. Es una limitación, no un bug: documentada aquí y a
> tener en cuenta al leer el índice en el Maestrat y Espadán.

### Normalización a 0..100

Percentiles sobre la serie histórica **local** de cada municipio, no umbrales
absolutos. Un FWI de 30 en el Maestrat húmedo no significa lo mismo que en el
Palancia.

**La distribución de referencia es una VENTANA ESTACIONAL móvil por día del año,
NO los 365 días.** La cadena FWI es continua todo el año (es física); la
distribución de referencia es estadística y va aparte: el FWI de hoy se compara
contra los mismos días del año (**±15 días**) de TODOS los años de la serie del
municipio.

```
comp_meteo = percentil(fwi_hoy, { FWI de los días [doy−15, doy+15] de todos los años }) * 100
```

**Por qué no los 365 días.** Con cadena continua, la mayoría de los días son
invierno con FWI ≈ 0; usar los 365 comprimiría la distribución hacia abajo y un
julio mediocre saldría en el percentil 85 solo por competir contra enero — el
índice saldría alto casi todo el verano y perdería poder discriminante, justo lo
que mide el AUC de Fase 4. La ventana ±15 días mide cada día contra su propia
época: un 23 de marzo se compara con finales de marzo de todos los años, no con
agosto. Es lo que permite que un evento de marzo (Villanueva de Viver) salga en su
cola alta si fue anómalo. (21 años × 31 días ≈ 650 muestras por día del año:
suficiente para un percentil estable; ampliable a ±30 si la cola alta lo pidiera.)
Es el criterio de los productos de anomalía de fire weather (EFFIS Anomaly,
climatología por ventana). **Decidido en Fase 2; se implementa en Fase 3** (es
`comp_meteo`).

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

> **⚠️ RIESGO ABIERTO (parcialmente resuelto en Fase 1).** La capa `SF.Forestal`
> —la que carga `terreno_forestal`— **no** contiene modelos de combustible. Pero
> la Fase 1 **localizó y cargó** la capa que sí los tiene:
> `Regulacion.Incendios.Combustible` del WFS del PATFOR (tabla
> `modelo_combustible_patfor`), esquema **Anderson/Rothermel**. De los 13 modelos
> aparecen 7 de vegetación (2..8) más un `0` = no combustible; faltan el 1 y
> 9..13 (ver `etl/reports/patfor-combustible-inventario.md`).
>
> La tabla de pesos de abajo ya está **adaptada a esos modelos de Anderson**.
> Queda pendiente:
> - **Fase 3:** el *overlay* espacial que asigna un modelo a cada polígono de
>   `terreno_forestal` (son cartografías distintas; su solape no es trivial). La
>   cobertura medida está en el inventario.
> - **Fase 4:** la calibración de los pesos (búsqueda en rejilla, docs/09).

Mapear el código de combustible de Anderson a un peso de inflamabilidad 0..1. De
los 13 modelos de Anderson, en la cartografía del PATFOR aparecen solo estos
(ordenados por peso):

| Modelo | Descripción | Peso |
|---|---|---|
| 4 | Matorral alto denso (~2 m) | 1,00 |
| 7 | Matorral bajo arbolado | 0,90 |
| 6 | Matorral medio (0,6–1,2 m) | 0,75 |
| 3 | Pastizal alto (0,75 m) | 0,70 |
| 2 | Pasto bajo arbolado | 0,60 |
| 5 | Matorral bajo verde (0,6 m) | 0,55 |
| 8 | Hojarasca compacta cerrada | 0,25 |

El código `0` = no combustible (peso 0; fuera del monte). Los modelos ausentes
en esta cartografía estática, con su peso de partida por si aparecen en una
revisión futura del PATFOR: 1 pastizal corto (0,45), 9 hojarasca de frondosa o
pinar (0,45), 10 arbolado con sotobosque y restos (0,85), 11 restos ligeros
(0,65), 12 restos medios (0,85), 13 restos pesados (0,95).

> **Pesos de partida, NO calibrados.** Derivados de las características de
> comportamiento publicadas de los modelos de Anderson (1982): velocidad de
> propagación, longitud de llama y resistencia al control. El modelo 4 es el
> techo por imposibilidad de ataque directo; el 7 y el 10 puntúan alto por
> continuidad vertical (combustible escalera), que es lo que convierte un fuego
> de superficie en fuego de copas. Los pastizales propagan muy rápido pero se
> detienen en discontinuidades, de ahí que no encabecen la tabla pese a su
> velocidad. Calibración en Fase 4 mediante la búsqueda en rejilla de docs/09.

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
