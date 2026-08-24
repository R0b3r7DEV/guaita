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
acertar). Mientras no haya perímetros de EFFIS, se aplica la opción **conservadora**:
los municipios **vecinos** (`ST_Touches`) del término de inicio, en la ventana
`[fecha_inicio, fecha_fin]` del incendio, se **excluyen de los negativos** — no se
cuentan como positivos (no se inventan etiquetas) ni como negativos. Afecta a la
interpretación de todas las métricas y queda declarado aquí.

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
