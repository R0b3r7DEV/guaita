#!/usr/bin/env python3
"""INE tabla 2865 (CSV) -> ine_code,poblacion.

Filtra Sexo="Total" y se queda con el Periodo (año) más reciente por municipio.
El código INE de 5 dígitos va embebido al inicio del campo "Municipios"
("12003 Albocàsser"). Población con separador de miles ".". Solo provincia 12.
Uso: parse_poblacion.py <csv_2865> <out.csv>
"""
import csv
import re
import sys


def main(src, out):
    best = {}
    with open(src, encoding="utf-8-sig") as f:
        for row in csv.reader(f, delimiter=";"):
            if len(row) != 4:
                continue
            muni, sexo, periodo, total = row
            if sexo.strip() != "Total":
                continue
            m = re.match(r"(\d{5})\s", muni)
            if not m or not m.group(1).startswith("12"):
                continue
            ine = m.group(1)
            try:
                year = int(periodo)
            except ValueError:
                continue
            pob = int(re.sub(r"[.\s]", "", total) or "0")
            if ine not in best or year > best[ine][0]:
                best[ine] = (year, pob)

    if len(best) != 135:
        sys.exit(f"ERROR: esperaba 135 municipios de Castellón, obtuve {len(best)}.")
    ceros = [k for k, v in best.items() if v[1] <= 0]
    if ceros:
        sys.exit(f"ERROR: población <= 0 en {ceros}.")

    with open(out, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["ine_code", "poblacion"])
        for ine in sorted(best):
            w.writerow([ine, best[ine][1]])
    print(f"OK: {len(best)} poblaciones (año {max(v[0] for v in best.values())}) -> {out}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("uso: parse_poblacion.py <csv_2865> <out.csv>")
    main(sys.argv[1], sys.argv[2])
