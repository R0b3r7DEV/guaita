#!/usr/bin/env python3
"""Genera comarcas_castellon.csv (ine_code -> comarca) desde el XLSX del PEGV.

Solo librería estándar (zipfile + xml): no depende de openpyxl ni pandas, así
corre en el contenedor GDAL sin instalar nada. Lo invoca build_comarcas.sh
(make comarcas), NO make seed. El XLSX es una tabla plana con columnas
provincia / comarca / código INE / municipio (hoja "mun com").

Uso: parse_comarcas.py <xlsx> <out.csv> <url> <fecha>
"""
import csv
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"


def _text(el):
    """Concatena todos los <t> descendientes (maneja runs de estilo)."""
    return "".join(t.text or "" for t in el.iter(NS + "t"))


def _shared_strings(z):
    if "xl/sharedStrings.xml" not in z.namelist():
        return []
    root = ET.fromstring(z.read("xl/sharedStrings.xml"))
    return [_text(si) for si in root.findall(NS + "si")]


def _rows(z, shared):
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


def main(xlsx, out, url, fecha):
    with zipfile.ZipFile(xlsx) as z:
        shared = _shared_strings(z)
        rows = list(_rows(z, shared))

    # Cabecera: la fila con celdas EXACTAS "comarca" y "municipio" (no por
    # subcadena: las filas de título contienen "códigos"/"INE" y falsearían la
    # detección). Mapear columnas por su etiqueta.
    cols, header_i = {}, None
    for i, r in enumerate(rows):
        low = {col: v.strip().lower() for col, v in r.items()}
        if "comarca" in low.values() and "municipio" in low.values():
            for col, v in low.items():
                if v == "provincia":
                    cols["prov"] = col
                elif v == "comarca":
                    cols["comarca"] = col
                elif "ine" in v:
                    cols["ine"] = col
                elif v == "municipio":
                    cols["muni"] = col
            header_i = i
            break
    if header_i is None or not {"ine", "comarca"} <= cols.keys():
        sys.exit("ERROR: no encuentro la cabecera provincia/comarca/código INE/municipio.")

    # provincia y comarca solo se rellenan en la primera fila de cada grupo:
    # se arrastran hacia abajo (forward-fill).
    out_rows, comarca_cur = [], ""
    for r in rows[header_i + 1:]:
        if r.get(cols["comarca"], "").strip():
            comarca_cur = r[cols["comarca"]].strip()
        ine = r.get(cols["ine"], "").strip()
        muni = r.get(cols.get("muni", ""), "").strip()
        if re.fullmatch(r"\d{5}", ine) and ine.startswith("12"):
            out_rows.append((ine, muni, comarca_cur))
    out_rows.sort()

    if len(out_rows) != 135:
        sys.exit(f"ERROR: esperaba 135 municipios de Castellón, obtuve {len(out_rows)}.")

    with open(out, "w", encoding="utf-8", newline="") as f:
        f.write("# comarcas_castellon.csv - municipio (codigo INE) -> comarca\n")
        f.write('# Fuente: Portal Estadistico GVA (PEGV), "Municipios y comarcas".\n')
        f.write(f"# URL: {url}\n")
        f.write(f"# Fecha de referencia: {fecha}. Codigos INE asignados por el INE.\n")
        f.write("# Comarcalizacion: disposicion adicional unica de la Ley 2/2020, de 2 de\n")
        f.write("#   diciembre, de Informacion Geografica y del Institut Cartografic Valencia.\n")
        f.write("# Generado por make comarcas (etl/parse_comarcas.py). NO editar a mano.\n")
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["ine_code", "municipio", "comarca"])
        w.writerows(out_rows)
    print(f"OK: {len(out_rows)} municipios de Castellon -> {out}")


if __name__ == "__main__":
    if len(sys.argv) != 5:
        sys.exit("uso: parse_comarcas.py <xlsx> <out.csv> <url> <fecha>")
    main(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4])
