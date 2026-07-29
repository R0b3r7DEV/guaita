#!/usr/bin/env python3
"""Estadísticos zonales por municipio para la topografía (Fase 1).

Lee cuatro rásters ALINEADOS (misma rejilla 25 m, EPSG:25830):
  - dem.vrt        : altitud (m), NoData -32767
  - slope_pct.tif  : pendiente en % (gdaldem slope -p)
  - aspect.tif     : orientación en grados (gdaldem aspect), NoData en llanos
  - zone.tif       : municipio rasterizado (valor = ine_code como entero; 0 = fuera)

Por municipio calcula, EXCLUYENDO NoData:
  - altitud_media_m     = media del DEM sobre tierra
  - pendiente_media_pct = media de la pendiente sobre tierra
  - pendiente_p90_pct   = percentil 90 de la pendiente (mandan los barrancos)
  - frac_solana         = (píxeles de tierra con aspect en [112,5, 247,5]) / tierra

Solo osgeo.gdal + numpy (ambos nativos en la imagen GDAL). Sin rasterstats.
Uso: zonal_topografia.py <dem.vrt> <slope_pct.tif> <aspect.tif> <zone.tif> <out.csv>
"""
import csv
import sys
import numpy as np
from osgeo import gdal


def band(path):
    ds = gdal.Open(path)
    if ds is None:
        sys.exit(f"ERROR: no puedo abrir {path}")
    b = ds.GetRasterBand(1)
    return b.ReadAsArray(), b.GetNoDataValue()


def main(dem_p, slope_p, aspect_p, zone_p, out):
    dem, dem_nd = band(dem_p)
    slope, slope_nd = band(slope_p)
    aspect, aspect_nd = band(aspect_p)
    zone, zone_nd = band(zone_p)
    dem = dem.astype("float64")
    slope = slope.astype("float64")
    aspect = aspect.astype("float64")

    zone_nd = 0 if zone_nd is None else zone_nd
    ines = np.unique(zone[zone != zone_nd]).astype(int)

    rows = []
    for ine in ines:
        m = zone == ine
        demv = dem[m]
        slv = slope[m]
        asv = aspect[m]

        tierra = demv != dem_nd                      # píxeles de tierra (DEM válido)
        n_tierra = int(tierra.sum())
        if n_tierra == 0:
            print(f"  AVISO: {ine:05d} sin píxeles de tierra (todo NoData) -> omitido",
                  file=sys.stderr)
            continue

        sl_ok = tierra & (slv != slope_nd)           # pendiente válida (no borde NoData)
        sl = slv[sl_ok]
        if sl.size == 0:
            print(f"  AVISO: {ine:05d} sin pendiente válida (n_tierra={n_tierra}) -> omitido",
                  file=sys.stderr)
            continue

        as_ok = tierra & (asv != aspect_nd)          # aspecto válido (no llano)
        solana = as_ok & (asv >= 112.5) & (asv <= 247.5)

        rows.append((
            f"{ine:05d}",
            round(float(sl.mean()), 2),
            round(float(np.percentile(sl, 90)), 2),
            round(float(solana.sum()) / n_tierra, 3),
            round(float(demv[tierra].mean()), 2),
        ))

    rows.sort()
    with open(out, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["ine_code", "pendiente_media_pct", "pendiente_p90_pct",
                    "frac_solana", "altitud_media_m"])
        w.writerows(rows)
    print(f"OK: {len(rows)} municipios -> {out}")


if __name__ == "__main__":
    if len(sys.argv) != 6:
        sys.exit("uso: zonal_topografia.py <dem.vrt> <slope_pct.tif> <aspect.tif> <zone.tif> <out.csv>")
    main(*sys.argv[1:6])
