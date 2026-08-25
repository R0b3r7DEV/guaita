# 09 · Validación del índice

Un índice que nadie ha comprobado es una opinión con decimales. Esta fase es la
que convierte GUAITA en trabajo defendible.

## Pregunta

¿El índice de GUAITA es más alto en los días y municipios donde efectivamente se
inició un gran incendio que en el resto?

## Conjunto de datos

**Positivos.** Pares (municipio, fecha) en los que se inició un incendio de
≥ 100 ha. Fuente: `incendio_historico`, cargado desde EFFIS + registro de la GVA.
Objetivo mínimo: **20 años**, 2005–2025. Los cuatro eventos recientes que ya
conocemos son la semilla, no el conjunto.

Si el histórico largo no está disponible, bajar el umbral a ≥ 50 ha para tener
suficientes positivos. Documentar la elección.

**Negativos.** Todos los demás pares (municipio, fecha) de la temporada de riesgo
(marzo–octubre). Son ~135 × 245 × 20 ≈ 660.000. Enorme desbalance: unos pocos
cientos de positivos frente a cientos de miles de negativos.

**Esto es clave y hay que tenerlo claro desde el principio:** con este desbalance,
la *accuracy* es una métrica inútil. Un modelo que diga siempre "no arde" acierta
el 99,9 %.

### Estado real de los positivos (EGIF cargado, 2026)

El WFS de EFFIS sigue caído, pero los positivos **no necesitan perímetros**: solo
(municipio, fecha, superficie). Fuente: **EGIF/MITECO** (herramienta oficial de
partes de incendio), cargada en `egif_incendio` (V14, `etl/load_positivos_egif.sh`).

- **26 incendios ≥ 100 ha en Castellón, 2005-2022** (el EGIF consolidado va con
  ~2 años de retraso; 2023-2026 aún no están). 25 mapean a su término de inicio por
  `idmunicipio`; el parte multi-término `12999` (fuego de 2012, 10.613 ha, el 2º
  mayor) se recupera por sus **coordenadas** de inicio (punto en municipio).
- **Superficie autoritativa = la FORESTAL del EGIF.** Bejís 2022 = **16.836 ha**
  (arbolada + no arbolada), NO los ~19.000 de prensa (que incluyen agrícola/total).
  La discrepancia se anota, no se esconde.
- **Corrección de un error de las semillas iniciales:** el supuesto "clúster de
  l'Alcalatén 2022" (que se había puesto a mano como les Useres/Costur/Figueroles/
  Llucena el 15-ago-2022) era en realidad **les Useres 2007 (5.775 ha)** más un
  **Costur pequeño el 14-ago-2022 (728 ha)**. El dato oficial manda; las 4 semillas
  aproximadas quedan superadas por el EGIF para el backtest.

### Corrección de método: un parte NO es un municipio

El EGIF da un parte por incendio con el **término de inicio** + la superficie total;
los municipios afectados (Bejís marcó 12) NO vienen enumerados. Construir los pares
como (término de inicio, fecha) metería Jérica, Viver o Torás en los **negativos** el
día que ardieron con Bejís → contaminación de etiquetas (el modelo se penaliza por
acertar).

**Corrección definitiva (perímetros ICV/GVA, 2026).** EFFIS sigue caído, pero los
perímetros de incendio del ICV (1993-2024, CC-BY) son su sustituto y traen justo lo
que faltaba: la geometría del **área quemada** y el número de parte EGIF
(`NumPIF_Min`). Cargados en `perimetro_incendio` (V15, `etl/load_perimetros_gva.sh`):
**1721 perímetros de Castellón 2005-2024**, y los **26 positivos casan con su
perímetro** por `numpif = numeroparte`. Verificado antes de fiarse: **Bejís 2022
cubre 9 municipios** (Bejís 95 % de su término, Torás 97 %, Teresa 86 %, Sacañet
76 %, más El Toro/Altura/Viver/Jérica/Barracas parciales) y **~17.300 ha** — en el
orden esperado (~12 munis / ~16.800 ha), ni 3 ni 40.000. Los 9 vs 12 de prensa: el
resto tuvo solape marginal o era zona evacuada no quemada.

Con eso, la exclusión de negativos deja de ser por vecindad aproximada (`ST_Touches`,
que excluía vecinos aunque no se quemaran) y pasa a ser por el **área quemada real**:
los términos que el perímetro del propio parte cubre (**≥10 ha**, salvo el de inicio),
en la ventana `[fecha_inicio, fecha_fin]`, se **excluyen de los negativos** — no se
cuentan como positivos (no se inventan etiquetas) ni como negativos. Los mismos
perímetros alimentan `f_tiempo` real (ver `Resultados con datos limpios`).

### Partición temporal usada

Con 22 positivos en 2005-2018 y solo 3 en 2019-2022, la validación de docs/09
(2005-2018 / 2019-2025) no diría nada (AUC out-of-sample ±0,30). Se mueve el corte a
**2005-2015 (calibración) / 2016-2022 (validación)** (~18/7): la calibración pierde
algo de potencia pero la validación pasa a ser informativa, que es lo que hace
defendible el resultado. **Se reportan los IC de ambos periodos.** Cuando lleguen los
positivos 2023-2026 (registro GVA, art. 50 Ley de Montes) la validación mejora.

## Métricas

| Métrica | Por qué | Objetivo |
|---|---|---|
| **AUC-ROC** | Capacidad de ordenar días peligrosos sobre no peligrosos | ≥ 0,75 |
| **AUC-PR** | La honesta con desbalance extremo | reportar siempre junto a ROC |
| **Sensibilidad a nivel 4-5** | ¿Qué fracción de GIF ocurrió en días marcados muy alto/extremo? | ≥ 0,70 |
| **Tasa de falsa alarma** | Días marcados 5 sin incendio / total días marcados 5 | reportar, no optimizar a ciegas |

**No optimizar la tasa de falsa alarma.** En prevención de incendios los falsos
positivos son baratos (un día más de vigilancia) y los falsos negativos son
catastróficos. La asimetría de coste debe declararse explícitamente en la página
de metodología.

## Protocolo

1. **Partición temporal, nunca aleatoria.** Calibrar con 2005–2018, validar con
   2019–2025. Una partición aleatoria filtra información del futuro al pasado a
   través de la autocorrelación meteorológica y da resultados falsamente buenos.
   Es el error metodológico más común en este tipo de trabajo.
2. **Descartar los 30 primeros días** de cada temporada (calentamiento del FWI).
3. **Ventana de atribución:** un incendio iniciado el día D se atribuye al índice
   de D. Comprobar también D−1 como análisis de sensibilidad, por si la ignición
   fue nocturna.
4. **Baseline obligatorio.** Comparar contra:
   - FWI crudo, sin componentes estructural ni de vulnerabilidad.
   - Solo temperatura máxima.
   - Nivel PREVIFOC oficial, si se consigue el histórico.

   **Si el índice compuesto no bate al FWI crudo, hay que decirlo.** Un resultado
   negativo honesto vale más que uno bueno maquillado, y quien te entreviste lo
   sabrá distinguir.

5. **Ablación.** Recalcular quitando un componente cada vez para saber cuál
   aporta. Es probable que `f_tiempo` (la curva en U de regeneración) sea la
   contribución más original; conviene medirla.

## Resultados con datos limpios (2026)

Primera medición sobre datos NO contaminados: `f_tiempo` real (perímetros ICV/GVA
2005-2024, 38 fuegos cumplen el reparto del 10 % en 31 municipios, ya no 1,00 en
todo) y contaminación de etiquetas corregida por el **área quemada real** (los
términos que cubre el perímetro del propio parte salen de los negativos, no por
vecindad `ST_Touches` sino por ≥10 ha quemadas). Corte 2005-2015 / 2016-2022.
Positivos en ventana: 15 calibración, 5 validación. AUC-ROC con IC 95 % (bootstrap
de valores de colocación, 2000 remuestras).

| Variante | AUC calib (2005-2015) | AUC valid (2016-2022) |
|---|---|---|
| `indice_compuesto` | 0,767 [0,700–0,834] | 0,752 [0,459–0,946] |
| `baseline_FWI_crudo` | **0,891** [0,822–0,951] | **0,819** [0,593–0,968] |
| `baseline_Tmax` | 0,571 | 0,746 |
| `baseline_estacional_doy` | 0,475 | 0,741 [0,405–0,927] |
| `ablacion_sin_meteo` (estructura+vulnerab) | 0,468 | 0,580 |
| `ablacion_sin_estructural` (meteo×vulnerab) | 0,663 | 0,679 |
| `ablacion_sin_vulnerab` (meteo×estructura) | 0,769 | 0,748 |

**El diagnóstico se sostiene: el índice compuesto NO bate al FWI crudo.** Y ahora
es un diagnóstico, no una conjetura: limpiar las etiquetas y darle a `f_tiempo`
datos reales lo movió un pelo (0,755→0,767 calib) pero no lo rescató. Las causas,
por ablación:

- **`comp_meteo` (percentil) tira magnitud que el FWI crudo conserva.** El percentil
  estacional borra a propósito el nivel absoluto del FWI, y ese nivel es justo lo
  que ordena los días de gran incendio. Es la pérdida principal.
- **`comp_vulnerab` no aporta nada:** `ablacion_sin_vulnerab` (0,769/0,748) ≈ el
  compuesto (0,767/0,752). La vulnerabilidad es lastre neutro, no señal.
- **La estructura sola es casi aleatoria para la ignición** (`sin_meteo`
  0,468/0,580): mide potencial de *propagación*, no de *ignición*. Coherente con
  que el índice mida "condiciones para un gran incendio", no dónde prende.

### El confound de estacionalidad (el análisis honesto)

`baseline_estacional_doy` solo conoce el calendario (cercanía al 1-ago, pico de la
temporada; fijo, no ajustado a los positivos). Su AUC mide cuánto de la separación
es **pura estación**, dado que casi todos los positivos son de verano:

- En **validación** llega a 0,741 — casi como el FWI crudo (0,819) y por encima del
  compuesto (0,752). Con solo 5 positivos (Bejís y compañía, agosto), "es verano"
  ya separa casi tan bien como el modelo entero. El IC [0,405–0,927] avisa de lo
  frágil que es.
- En **calibración** cae a 0,475 (peor que el azar): con 15 positivos repartidos
  por la temporada, la cercanía al 1-ago no discrimina.

La lectura: **una parte grande del AUC del FWI crudo en validación es
estacionalidad**, pero el FWI lleva señal meteorológica real más allá de la
estación — visible en calibración, donde con más n el FWI (0,891) despega del
baseline estacional (0,475). Por eso, si al final se prefiere el FWI **absoluto**
sobre el percentil, será **por utilidad operativa** (conservar la magnitud que
ordena los grandes incendios), no porque su AUC sea mayor "gratis": buena parte de
esa ventaja es codificar el verano.

### Villanueva de Viver 2023: el percentil no entierra el absoluto

El argumento de partida era que el percentil rescata anomalías de temporada baja
que el absoluto entierra. Villanueva de Viver (2023-03-23), el caso que mejor
discrimina las dos vías, lo **debilita**: su FWI absoluto ese día (32,91) es el
**P83 del año**, por encima de la media de agosto (26,0) — no está enterrado. El
percentil lo marca P95, sí, pero el absoluto ya lo pone alto. La ventaja del
percentil en temporada baja es menor de lo que se asumió; anotado para no venderla
de más.

### Sensibilidad y falsa alarma (nivel ≥ 4)

Sensibilidad del compuesto: 0,267 (calib) / 0,600 (valid). Falsa alarma ≈ 1,000 en
ambos y AUC-PR ≈ 0,000: es la realidad del desbalance extremo (20 positivos frente
a ~10⁶ pares municipio-día), no un fallo aislado del modelo. Por eso el AUC-PR se
reporta pero no decide, y ningún umbral fijo de nivel da precisión utilizable a
esta tasa base. La utilidad es ordenar, no clasificar con un corte.

## Decisión de forma (2026): variantes medidas, sin fijar pesos

Antes de calibrar, se decide la **forma** del índice midiendo variantes con el arnés.
Criterio: **AUC de CALIBRACIÓN** (2005-2015, 15 positivos, IC estrecho); la validación
(5 positivos, IC ±0,25) se reporta pero no decide.

### comp_meteo (dentro del compuesto multiplicativo actual)

| Variante de comp_meteo | AUC calib | AUC valid |
|---|---|---|
| percentil estacional (actual) | 0,767 [0,696–0,832] | 0,752 |
| absoluto (percentil provincial) | 0,785 [0,724–0,844] | 0,790 |
| híbrido geométrico | 0,782 [0,724–0,841] | 0,778 |
| híbrido máximo | 0,759 [0,696–0,823] | 0,768 |

El **absoluto** bate al percentil (0,785 vs 0,767), coherente con el diagnóstico
—el percentil tira magnitud—, pero los IC se solapan y la mejora es modesta. Ningún
compuesto multiplicativo se acerca al FWI crudo (0,891): cambiar comp_meteo no es
donde está el problema.

### Estructura de combinación (el hallazgo)

| Estructura | AUC calib | AUC valid |
|---|---|---|
| **`baseline_FWI_crudo`** | **0,891** [0,826–0,951] | 0,819 |
| compuesto multiplicativo (actual) | 0,767 [0,696–0,832] | 0,752 |
| solo meteo (percentil) | 0,891 [0,787–0,966] | 0,753 |
| solo meteo (absoluto) | 0,891 [0,826–0,951] | 0,819 |
| meteo × modulador estructural [0,8–1,2] (percentil) | 0,871 [0,773–0,941] | 0,763 |
| **meteo × modulador estructural [0,8–1,2] (absoluto)** | **0,876** [0,820–0,927] | 0,827 |

**El multiplicador de rango completo destruye la señal meteo.** Multiplicar la meteo
(0,891) por `sqrt(0,65·ce+0,35·cv)` la hunde a 0,767. Un **modulador acotado**
—meteo de base, estructura moviendo el resultado solo en una banda [0,8–1,2]—
recupera casi todo (0,876), con su IC inferior (0,820) por encima del punto del
compuesto actual (0,767): la diferencia es real, no ruido. Solo-meteo iguala al FWI
crudo por construcción pero **tira la estructura**, que sí aporta (siguiente sección).

### La estructura predice el TAMAÑO, no la ignición

`ablacion_sin_meteo` = 0,468 (azar) dice que estructura+vulnerabilidad no predicen
DÓNDE prende — lógico, la ignición es humana y se concentra cerca de la gente, no en
el combustible más continuo. Pero esa es la métrica equivocada para este componente.
Correlación de `comp_estructural` del término de inicio con la **superficie final** de
los 26 positivos (n=24 con fila de índice):

| | valor |
|---|---|
| Pearson(ce, superficie) | 0,341 |
| Pearson(ce, ln superficie) | 0,496 |
| **Spearman(ce, superficie)** | **0,616** |
| Pearson(comp_meteo, superficie) | 0,190 |

**`comp_estructural` predice el tamaño condicionado a que haya ignición** (Spearman
0,616, moderada-fuerte, p≈0,001), muy por encima de comp_meteo (0,190). Está haciendo
su trabajo: la meteo dice cuándo hay peligro de ignición, la estructura cuánto puede
crecer. Por eso el componente **no se descarta**; cambia de rol, de multiplicador de
rango completo a **modulador de severidad acotado**.

### Chequeo operativo (lo que el AUC no ve)

Villanueva de Viver 2023-03-23 (4.700 ha, temporada baja), compuesto y nivel por
variante de comp_meteo:

| Variante | índice | nivel |
|---|---|---|
| percentil | 66,35 | 4 |
| absoluto | 62,11 | 4 |
| híbrido geométrico | 64,20 | 4 |
| híbrido máximo | 66,35 | 4 |

**Ninguna lo entierra:** el absoluto lo deja en nivel 4 (idx 62), no en nivel 2. Se
despeja la objeción de que el absoluto pierde anomalías de temporada baja — su FWI
ese día ya era P83 provincial.

### Recomendación de forma (a calibrar después)

Meteo **absoluta** (percentil provincial) de base, `comp_estructural` como **modulador
acotado** de severidad, `comp_vulnerab` fuera del número (lastre neutro con el proxy
de población actual; se muestra como contexto). AUC calib 0,876 [0,820–0,927] —
indistinguible del FWI crudo— pero conservando la información de severidad que el FWI
crudo no tiene. La banda [0,8–1,2] y los pesos quedan para la calibración; aquí solo
se decide la forma.

## Calibración

Los parámetros calibrables están en `config/modelo-v1.yml`:

- Puntos de la curva `f_tiempo`.
- Pesos de `comp_estructural` (0,65) y `comp_vulnerab` (0,35).
- Exponente de `frac_forestal` (0,5).
- Pesos de inflamabilidad por modelo de combustible.

Búsqueda en rejilla sobre el periodo de calibración, maximizando AUC-PR.
**Al cambiar cualquiera, subir `version_modelo` y recalcular todo el histórico.**

## Limitaciones a publicar

Sin esta sección la página de metodología es propaganda. Debe decir, como mínimo:

- El índice mide **condiciones favorables a un gran incendio**, no probabilidad
  de ignición. La ignición depende sobre todo de factores humanos —negligencia,
  maquinaria, intencionalidad— que el modelo no observa. Bejís 2022 se inició por
  un rayo; Espadà 2026 está bajo investigación con indicios que descartan causa
  natural. Ninguno de los dos era predecible como suceso.
- La resolución municipal es gruesa. Un término de 100 km² tiene laderas muy
  distintas y un solo número no las representa.
- Los datos meteorológicos interpolados en zonas de montaña tienen error
  significativo, reflejado en `calidadDato`.
- El componente estructural depende de cartografía PATFOR que puede tener años.
- El conjunto de positivos es pequeño. Los intervalos de confianza del AUC son
  anchos y hay que publicarlos por bootstrap, no dar un número pelado.

## Reproducibilidad

`make backtest` ejecuta todo el pipeline y escribe en `reports/`:
- `backtest-{version}.json` con las métricas.
- Curvas ROC y PR en PNG.
- Tabla de ablación.

Debe correr sin red, sobre un dump de la BD versionado. Un backtest que no se
puede reproducir no es un backtest.
