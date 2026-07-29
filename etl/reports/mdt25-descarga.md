# MDT25 — lista de descarga manual (23 hojas MTN50, Castellón continental)

> Generado por la consulta espacial malla MTN50 ∩ geometría continental
> (`mv_provincia_continental`). No es un inventario del seed: es la **lista de
> la compra** para bajar las teselas a mano (Opción C). NO editar a mano.

## Producto y licencia

- **Producto:** Modelo Digital del Terreno **MDT25 — 1ª cobertura** (PNOA-MDT25).
- **codSerie CNIG:** `02107`.
- **Portal:** https://centrodedescargas.cnig.es/CentroDescargas/modelo-digital-terreno-mdt25-primera-cobertura
- **Licencia:** **CC-BY 4.0** (IGN, Orden FOM/2807/2015). Permite redistribución con atribución.
- **Atribución obligatoria** (a mostrar junto a los datos y en el Release):
  `MDT25 1ª cobertura CC-BY 4.0 ign.es` — © Instituto Geográfico Nacional (IGN)/CNIG.

## Variantes y por qué

Todo debe quedar **nativo en EPSG:25830** (ETRS89/UTM30N) **sin reproyectar el ráster**
(reproyectar un MDE interpola y contamina las pendientes). Por eso:
- Hojas de **huso 30** → versión **normal** (ya es 25830).
- Hojas de **huso 31** (costa este, al este del meridiano 0°) → versión **«huso 30 extendido»**
  que el CNIG ofrece precisamente para tenerlas en 25830.
- El nombre de fichero objetivo lleva `HU30` en las 23. **El criterio DEFINITIVO de aceptación**
  es `gdalinfo` reportando **EPSG:25830**; si alguna sale en 25831, es la variante equivocada.

| Hoja | Nombre | Huso (geometría) | Variante a descargar | Fichero esperado |
|---|---|---|---|---|
| 0519 | Aguaviva | 30 | Huso 30 (normal) | `PNOA-MDT25-ETRS89-HU30-0519-LID.TIF` |
| 0520 | Peñarroya de Tastavíns | 30 | Huso 30 (normal) | `PNOA-MDT25-ETRS89-HU30-0520-LID.TIF` |
| 0521 | Beceite | 31 | **Huso 30 EXTENDIDO** | `PNOA-MDT25-ETRS89-HU30-0521-LID.TIF` |
| 0544 | Forcall | 30 | Huso 30 (normal) | `PNOA-MDT25-ETRS89-HU30-0544-LID.TIF` |
| 0545 | Morella | 30 | Huso 30 (normal) | `PNOA-MDT25-ETRS89-HU30-0545-LID.TIF` |
| 0546 | Ulldecona | 31 | **Huso 30 EXTENDIDO** | `PNOA-MDT25-ETRS89-HU30-0546-LID.TIF` |
| 0547 | Alcanar | 31 | **Huso 30 EXTENDIDO** | `PNOA-MDT25-ETRS89-HU30-0547-LID.TIF` |
| 0569 | Villafranca del Cid | 30 | Huso 30 (normal) | `PNOA-MDT25-ETRS89-HU30-0569-LID.TIF` |
| 0570 | Albocàsser | 30 | Huso 30 (normal) | `PNOA-MDT25-ETRS89-HU30-0570-LID.TIF` |
| 0571 | Vinaròs | 31 | **Huso 30 EXTENDIDO** | `PNOA-MDT25-ETRS89-HU30-0571-LID.TIF` |
| 0591 | Mora de Rubielos | 30 | Huso 30 (normal) | `PNOA-MDT25-ETRS89-HU30-0591-LID.TIF` |
| 0592 | Villahermosa del Río | 30 | Huso 30 (normal) | `PNOA-MDT25-ETRS89-HU30-0592-LID.TIF` |
| 0593 | Les Coves de Vinromà | 30 | Huso 30 (normal) | `PNOA-MDT25-ETRS89-HU30-0593-LID.TIF` |
| 0594 | Alcalà de Xivert | 31 | **Huso 30 EXTENDIDO** | `PNOA-MDT25-ETRS89-HU30-0594-LID.TIF` |
| 0614 | Sarrión | 30 | Huso 30 (normal) | `PNOA-MDT25-ETRS89-HU30-0614-LID.TIF` |
| 0615 | L'Alcora | 30 | Huso 30 (normal) | `PNOA-MDT25-ETRS89-HU30-0615-LID.TIF` |
| 0616 | Benicàssim/Benicasim | ≈0° (borde; geom 31 / oficial 30) | Huso 30 (normal) | `PNOA-MDT25-ETRS89-HU30-0616-LID.TIF` |
| 0639 | Jérica | 30 | Huso 30 (normal) | `PNOA-MDT25-ETRS89-HU30-0639-LID.TIF` |
| 0640 | Onda | 30 | Huso 30 (normal) | `PNOA-MDT25-ETRS89-HU30-0640-LID.TIF` |
| 0641 | Castelló de la Plana | 30 | Huso 30 (normal) | `PNOA-MDT25-ETRS89-HU30-0641-LID.TIF` |
| 0667 | Villar del Arzobispo | 30 | Huso 30 (normal) | `PNOA-MDT25-ETRS89-HU30-0667-LID.TIF` |
| 0668 | Sagunt/Sagunto | 30 | Huso 30 (normal) | `PNOA-MDT25-ETRS89-HU30-0668-LID.TIF` |
| 0669 | Moncofa | 30 (borde 0°; servida HU30) | Huso 30 (normal) | `PNOA-MDT25-ETRS89-HU30-0669-LID.TIF` |

**Nota 0669 (Moncofa) — añadida en v2:** vecina este de 0668. El término de
Moncofa (X 741.317–746.228 en 25830) cae al **este del extent UTM de la tesela
0668** (que acaba en X≈741.338), así que con solo 0668 el municipio quedaba casi
todo en NoData y salía con topografía degenerada (`frac_solana=1,000`,
pendiente≈0). Con 0669 (extent X 740.588–769.938) queda cubierto. La hoja roza el
meridiano 0°: el IGN la sirve como **HU30 (25830)**; `gdalinfo` lo confirma. El
loader añadió una **aserción de cobertura mínima de píxeles** para que un hueco de
tesela así vuelva a fallar ruidosamente en vez de colarse con números plausibles.

**Nota 0616 (Benicàssim):** partida casi exactamente por el meridiano 0° (centroide
+0,006°). La geometría la daría huso 31, pero el IGN la clasifica huso 30 y sirve su
MDT25 como HU30 (25830). Se baja la **variante huso 30 normal**. `gdalinfo` lo confirma.

## Descarga manual paso a paso (para repetir dentro de ~1 año)

1. Abre el portal del MDT25 1ª cobertura (URL arriba).
2. Para cada hoja de la tabla: localízala por su número MTN50 (buscador «Hoja MTN50»
   o navegando el mapa). Descarga la **variante** indicada en su fila.
3. Guarda las 23 en `data/mdt25/` con el nombre `PNOA-MDT25-ETRS89-HU30-XXXX-LID.TIF`.
4. Ejecuta `make mdt-fetch`: valida que están las 23, que cada una es EPSG:25830
   (`gdalinfo`), genera `SHA256SUMS` y empaqueta `mdt25-castellon.tar.gz`.
5. Publica el Release `data/mdt25-v2` con ese tar.gz (comandos aparte).

