# Perímetros históricos de incendio (f_tiempo, Fase 3)

`incendio_historico` alimenta `f_tiempo` (docs/04 §2.4): los años desde el último
incendio por municipio, con el criterio de reparto (`reparto-min-frac-forestal`,
10 % de la superficie forestal municipal cubierta por el perímetro).

Mientras EFFIS no es accesible, `load_incendios.sh` carga **4 semillas** conocidas
(`mappings/incendios_semilla.csv`) y el resto de municipios queda neutro
(`sin-dato-valor = 1.0`): el índice se calcula igual y el sistema es shippable.

## Comprobar el WFS de EFFIS (al empezar cada sesión)

El backend Oracle del WFS `ercc.ba` lleva caído toda la Fase 3. Un solo intento,
sin insistir:

```bash
curl -s --max-time 60 "https://ies-ows.jrc.ec.europa.eu/effis?service=WFS&version=1.1.0&request=GetFeature&typename=ercc.ba&srsname=EPSG:4326&bbox=39.7,-0.8,40.8,0.3,EPSG:4326&maxfeatures=1&outputformat=geojson"
```

- Devuelve `OracleSpatial error` / `Exception` → sigue caído. No insistir.
- Devuelve un `FeatureCollection` → revivió: descarga los perímetros a
  `data/incendios.gpkg` y ve a «Cuando llegue el fichero».

## Cuando llegue el fichero (WFS resucitado o formulario)

Petición del formulario (`effis.jrc.ec.europa.eu/apps/data.request.form`):
producto **Burnt Area / Final Fire Perimeters**, rango **2000–2026**, ámbito
**España** (o Castellón), formato **Shapefile o GeoPackage**, con **fecha de
inicio** y **superficie (ha)**.

1. Coloca el fichero en `data/incendios.gpkg` (o `.shp`).
2. Ajusta el mapeo de campos en `mappings/incendios-effis.env`. Verifica los
   nombres reales de las columnas:
   ```bash
   ogrinfo -so -al data/incendios.gpkg
   ```
3. Re-ejecuta `make seed`. El loader reproyecta a 25830 (con verificación
   empírica), recorta contra el continente + 5 km, **reconcilia las semillas**
   (solape espacial + fecha ±15 d → mismo evento, EFFIS manda, discrepancia al
   log) y carga de forma idempotente.

## Aserciones (fallan ruidosamente)

- **a)** los 10 municipios de los eventos conocidos están cubiertos por algún
  perímetro (incluido el clúster de l'Alcalatén, que EFFIS podría trocear por
  término bajo el umbral de área).
- **b)** Bejís ~19.000 ha y Villanueva de Viver ~4.700 ha (tolerancia de orden de
  magnitud: caza un fallo de unidades o de recorte).
- **c)** años EFFIS dentro de 2000–2026 (solo con fichero real).
- **d)** recuento por año en `reports/incendios-inventario.md` (2012/2022/2023
  deberían destacar).

## Versionado si EFFIS llega después de publicar

Si aún **no** se ha publicado nada, EFFIS entra como **recálculo bajo la misma
`version_modelo`** (`v1.0`): no cambia la definición del modelo, solo completa un
dato de entrada que faltaba. Si **ya** hay algo publicado con `f_tiempo=1.0`,
entra como **`v1.1`** para no reescribir en silencio un histórico ya mostrado.
