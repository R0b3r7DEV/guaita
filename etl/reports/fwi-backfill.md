# Informe del backfill FWI

Generado tras el backfill (docs/04). Serie continua, calentamiento excluido.

Municipios en la serie: **135** (135 = provincia completa; menos = subconjunto eventos+control). Filas FWI: 1065825.

## a) Por año (sin calentamiento)

| año | FWI medio | máx | P90 | P95 | P99 |
|---|---|---|---|---|---|
| 2005 | 17.9 | 73.1 | 36.5 | 41.4 | 49.3 |
| 2006 | 15.5 | 78.3 | 35.6 | 40.2 | 48.4 |
| 2007 | 16.3 | 79.9 | 35.6 | 40.7 | 53.6 |
| 2008 | 12.1 | 85.7 | 29.4 | 33.8 | 44.9 |
| 2009 | 15.7 | 77.5 | 35.6 | 40.7 | 50.7 |
| 2010 | 12.8 | 71.7 | 30.7 | 35.7 | 43.6 |
| 2011 | 14.3 | 94.4 | 32.8 | 37.9 | 49.6 |
| 2012 | 19.7 | 84.0 | 40.6 | 46.1 | 58.5 |
| 2013 | 17.1 | 80.7 | 34.8 | 39.8 | 50.7 |
| 2014 | 15.7 | 80.7 | 34.2 | 39.1 | 50.6 |
| 2015 | 14.2 | 82.6 | 33.7 | 39.4 | 50.8 |
| 2016 | 16.8 | 77.6 | 37.6 | 42.6 | 50.9 |
| 2017 | 18.5 | 85.6 | 35.4 | 40.2 | 48.5 |
| 2018 | 12.5 | 71.2 | 31.0 | 37.2 | 47.8 |
| 2019 | 19.1 | 73.8 | 37.5 | 43.2 | 55.1 |
| 2020 | 12.9 | 75.4 | 32.1 | 37.0 | 44.6 |
| 2021 | 9.3 | 79.3 | 22.9 | 29.8 | 42.0 |
| 2022 | 12.6 | 80.5 | 32.6 | 40.4 | 50.7 |
| 2023 | 20.5 | 77.4 | 37.6 | 43.6 | 55.8 |
| 2024 | 16.5 | 78.8 | 37.2 | 42.3 | 51.2 |
| 2025 | 10.7 | 65.8 | 26.8 | 33.0 | 43.8 |
| 2026 | 17.8 | 80.2 | 41.6 | 48.3 | 59.6 |

## b) Validación de eventos (percentil en ventana ±15 días del municipio)

| evento | fecha | FWI | percentil |
|---|---|---|---|
| Bejís | 2022-08-15 | 51.2 | P92.9 |
| Jérica | 2022-08-15 | 54.1 | P97.1 |
| Viver | 2022-08-15 | 53.6 | P96.8 |
| Torás | 2022-08-15 | 52.5 | P93.5 |
| les Useres | 2022-08-15 | 36.3 | P93.8 |
| Costur | 2022-08-15 | 50.1 | P98.9 |
| Figueroles | 2022-08-15 | 38.3 | P95.0 |
| Llucena | 2022-08-15 | 39.4 | P94.1 |
| Villanueva de Viver | 2023-03-23 | 32.9 | P95.5 |
| la Vall d'Uixó | 2026-07-25 | 68.7 | P100.0 |

## c) 6 municipios de control — FWI medio de julio

| municipio | FWI medio julio |
|---|---|
| Moncofa | 30.7 |
| Vistabella | 17.0 |
| Villahermosa | 18.4 |
| Morella | 23.0 |
| Nules | 29.1 |
| Almenara | 31.4 |

## d) Gradiente provincial — FWI medio de julio por comarca

| comarca | municipios | FWI medio julio | mín | máx |
|---|---|---|---|---|
| El Alto Palancia | 27 | 31.7 | 27.4 | 37.6 |
| El Baix Maestrat | 18 | 31.4 | 23.8 | 37.6 |
| La Plana Baixa | 20 | 29.6 | 23.8 | 33.7 |
| La Plana Alta | 16 | 28.3 | 21.8 | 35.4 |
| El Alto Mijares | 22 | 27.9 | 18.4 | 32.6 |
| Els Ports | 14 | 22.3 | 16.0 | 25.6 |
| L'Alcalatén | 6 | 21.9 | 17.1 | 28.6 |
| L'Alt Maestrat | 12 | 21.6 | 17.0 | 28.0 |

Lectura: mayor FWI medio de julio en **El Alto Palancia** (31.7); menor en **L'Alt Maestrat** (21.6). Si la cabeza es litoral (cálido-seco) y la cola de interior de montaña (húmedo-fresco), el gradiente costa/interior de la muestra de 16 se confirma a escala provincial.
