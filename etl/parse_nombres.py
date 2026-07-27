#!/usr/bin/env python3
"""Extrae ine_code -> nombre del GML INSPIRE AU municipal del CNIG.

ine_code = últimos 5 dígitos de au:nationalCode (verificado: 34+CCAA+PROV+INE5).
nombre   = primer au:name/.../gn:text.
Se hace aquí (no vía ogr) para no depender de cómo ogr aplana el nombre anidado
del esquema INSPIRE. Solo stdlib con iterparse (memoria acotada; el GML pesa
~140 MB). Filtra provincia 12 (Castellón). Uso: parse_nombres.py <gml> <out.csv>
"""
import csv
import sys
import xml.etree.ElementTree as ET


def local(tag):
    return tag.rsplit("}", 1)[-1]


def main(gml, out):
    rows = {}
    for _, el in ET.iterparse(gml, events=("end",)):
        if local(el.tag) != "AdministrativeUnit":
            continue
        natcode = nombre = None
        for sub in el.iter():
            ln = local(sub.tag)
            if ln == "nationalCode" and sub.text:
                natcode = sub.text.strip()
            elif ln == "text" and nombre is None and sub.text:
                nombre = sub.text.strip()
        if natcode:
            ine = natcode[-5:]
            if ine.startswith("12") and ine not in rows:
                rows[ine] = nombre or ""
        el.clear()

    if len(rows) != 135:
        sys.exit(f"ERROR: esperaba 135 municipios de Castellón, obtuve {len(rows)}.")

    with open(out, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["ine_code", "nombre"])
        for ine in sorted(rows):
            w.writerow([ine, rows[ine]])
    print(f"OK: {len(rows)} nombres -> {out}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("uso: parse_nombres.py <gml> <out.csv>")
    main(sys.argv[1], sys.argv[2])
