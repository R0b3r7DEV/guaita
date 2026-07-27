#!/usr/bin/env python3
"""INE padrón (pobmun.zip) -> ine_code,poblacion.

Fuente ROBUSTA: pobmunYY.xlsx trae CPRO (provincia, 2 díg.), CMUN (municipio,
3 díg.) y POBYY (población) como CAMPOS DEDICADOS, no como una cadena de
presentación. Por eso se prefiere a la tabla 2865, cuyo código INE habría que
parsearlo del texto "12003 Albocàsser" (formato de display, cambiable).
ine_code = CPRO + CMUN (con ceros). Se elige el pobmunYY.xlsx más reciente del
ZIP. Filtra provincia 12. Solo stdlib. Uso: parse_poblacion.py <pobmun.zip> <out.csv>
"""
import csv
import io
import re
import sys
import zipfile
import xml.etree.ElementTree as ET

NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"


def _text(el):
    return "".join(t.text or "" for t in el.iter(NS + "t"))


def _latest_member(z):
    """El pobmunYY.xlsx del año más alto (YY<=89 -> 20YY, si no 19YY)."""
    best = None
    for name in z.namelist():
        m = re.search(r"pobmun(\d{2})\.xlsx$", name)
        if not m:
            continue
        yy = int(m.group(1))
        year = 2000 + yy if yy <= 89 else 1900 + yy
        if best is None or year > best[0]:
            best = (year, name)
    if best is None:
        sys.exit("ERROR: no hay pobmunYY.xlsx en el ZIP.")
    return best


def _rows(xlsx_bytes):
    z = zipfile.ZipFile(io.BytesIO(xlsx_bytes))
    shared = []
    if "xl/sharedStrings.xml" in z.namelist():
        shared = [_text(si) for si in
                  ET.fromstring(z.read("xl/sharedStrings.xml")).findall(NS + "si")]
    sheet = ET.fromstring(z.read("xl/worksheets/sheet1.xml"))
    for row in sheet.iter(NS + "row"):
        cells = {}
        for c in row.findall(NS + "c"):
            col = re.match(r"[A-Z]+", c.get("r")).group(0)
            t = c.get("t")
            v = c.find(NS + "v")
            if t == "s" and v is not None and v.text is not None:
                val = shared[int(v.text)]
            elif t == "inlineStr":
                val = _text(c)
            elif v is not None and v.text is not None:
                val = v.text
            else:
                val = ""
            cells[col] = val.strip()
        yield cells


def main(zippath, out):
    with zipfile.ZipFile(zippath) as z:
        year, name = _latest_member(z)
        rows = list(_rows(z.read(name)))

    # Cabecera: fila con CPRO y CMUN; la población es la columna POB<año>.
    cols, header_i = {}, None
    for i, r in enumerate(rows):
        up = {c: v.strip().upper() for c, v in r.items()}
        if "CPRO" in up.values() and "CMUN" in up.values():
            for c, v in up.items():
                if v == "CPRO":
                    cols["cpro"] = c
                elif v == "CMUN":
                    cols["cmun"] = c
                elif v.startswith("POB"):
                    cols["pob"] = c
            header_i = i
            break
    if header_i is None or not {"cpro", "cmun", "pob"} <= cols.keys():
        sys.exit("ERROR: no encuentro cabecera CPRO/CMUN/POB en pobmun.")

    best = {}
    for r in rows[header_i + 1:]:
        cpro = r.get(cols["cpro"], "").strip()
        cmun = r.get(cols["cmun"], "").strip()
        pob = r.get(cols["pob"], "").strip()
        if cpro != "12":
            continue
        if not (cpro.isdigit() and cmun.isdigit()):
            sys.exit(f"ERROR: CPRO/CMUN no numéricos: {cpro!r}/{cmun!r} (¿cambió el formato?)")
        ine = f"{int(cpro):02d}{int(cmun):03d}"
        try:
            best[ine] = int(re.sub(r"[.\s]", "", pob))
        except ValueError:
            sys.exit(f"ERROR: población no entera para {ine}: {pob!r}")

    if len(best) != 135:
        sys.exit(f"ERROR: esperaba 135 municipios (CPRO=12), obtuve {len(best)}.")
    ceros = [k for k, v in best.items() if v <= 0]
    if ceros:
        sys.exit(f"ERROR: población <= 0 en {ceros}.")

    with open(out, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["ine_code", "poblacion"])
        for ine in sorted(best):
            w.writerow([ine, best[ine]])
    print(f"OK: {len(best)} poblaciones (pobmun, padrón {year}) -> {out}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("uso: parse_poblacion.py <pobmun.zip> <out.csv>")
    main(sys.argv[1], sys.argv[2])
