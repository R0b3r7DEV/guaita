#!/usr/bin/env bash
# Validación TEMPRANA de la meteo ingerida (README, "Despliegue en VPS").
# Se corre DESPUÉS DE CADA TRAMO y ANTES de lanzar el siguiente: si el T1
# ingiere con la hora equivocada o sin corrección altitudinal, se caza el día 1
# y no el día 3 con la serie entera contaminada. Sale 1 si algo falla.
set -euo pipefail

[ -f docker-compose.yml ] || {
  echo "ejecuta desde la raíz del repo" >&2
  exit 2
}
COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.vps.yml)
PGUSER="$("${COMPOSE[@]}" exec -T db printenv POSTGRES_USER | tr -d '\r\n')"
PGDB="$("${COMPOSE[@]}" exec -T db printenv POSTGRES_DB | tr -d '\r\n')"

# scalar: valor único, sin cabecera; table: salida tabular legible.
scalar() { "${COMPOSE[@]}" exec -T db psql -U "$PGUSER" -d "$PGDB" -v ON_ERROR_STOP=1 -tAc "$1"; }
table() { "${COMPOSE[@]}" exec -T db psql -U "$PGUSER" -d "$PGDB" -v ON_ERROR_STOP=1 -c "$1"; }

fallos=0
ok() { echo "  OK   $1"; }
mal() {
  echo "  MAL  $1"
  fallos=$((fallos + 1))
}

echo "== backfill-check =="
total="$(scalar "select count(*) from meteo_municipio")"
if [ "$total" = "0" ]; then
  echo "no hay meteo ingerida todavía; lanza un tramo primero."
  exit 1
fi

# 1) Cobertura rectangular: N municipios, todos con el MISMO nº de días.
# Esperados = los de la provincia (135), salvo override para el ensayo en seco
# (GUAITA_BACKFILL_EXPECT_MUNIS=5).
munis="$(scalar "select count(distinct ine_code) from meteo_municipio")"
esperados="${GUAITA_BACKFILL_EXPECT_MUNIS:-$(scalar "select count(*) from municipio")}"
if [ "$munis" = "$esperados" ]; then
  ok "municipios con dato: $munis/$esperados"
else
  mal "municipios con dato: $munis/$esperados (faltan municipios)"
fi

ndist="$(scalar "select count(distinct n) from (select count(*) n from meteo_municipio group by ine_code) t")"
dias="$(scalar "select count(distinct fecha) from meteo_municipio")"
maxmuni="$(scalar "select max(n) from (select count(*) n from meteo_municipio group by ine_code) t")"
rango_fechas="$(scalar "select min(fecha) || ' .. ' || max(fecha) from meteo_municipio")"
if [ "$ndist" = "1" ] && [ "$maxmuni" = "$dias" ]; then
  ok "días por municipio uniforme: $dias días ($rango_fechas)"
else
  mal "días por municipio NO uniforme (distintos=$ndist, máx=$maxmuni, fechas=$dias): hay municipios rezagados"
fi

# 2) Corrección altitudinal poblada (no NULL).
nulos="$(scalar "select count(*) from meteo_municipio where elevacion_celda_m is null or delta_altitud_m is null")"
if [ "$nulos" = "0" ]; then
  ok "elevacion_celda_m y delta_altitud_m poblados en todas las filas"
else
  mal "$nulos filas con elevacion_celda_m/delta_altitud_m NULL"
fi

# 3) Rangos físicos.
fuera="$(scalar "select count(*) from meteo_municipio where temp_12utc_c < -20 or temp_12utc_c > 50 or hr_12utc_pct < 0 or hr_12utc_pct > 100 or viento_12utc_kmh < 0")"
if [ "$fuera" = "0" ]; then
  ok "rangos físicos (temp -20..50, HR 0..100, viento >= 0)"
else
  mal "$fuera filas fuera de rango físico (temp/HR/viento)"
fi

# Días de verano/invierno disponibles en lo ingerido.
diaV="$(scalar "select max(fecha) from meteo_municipio where extract(month from fecha) = 7")"
diaI="$(scalar "select max(fecha) from meteo_municipio where extract(month from fecha) = 1")"

# 4) Estacionalidad: verano más cálido que invierno (si sale al revés -> hora/zona).
if [ -n "$diaV" ] && [ -n "$diaI" ]; then
  tV="$(scalar "select round(avg(temp_12utc_c),1) from meteo_municipio where fecha = '$diaV'")"
  tI="$(scalar "select round(avg(temp_12utc_c),1) from meteo_municipio where fecha = '$diaI'")"
  if awk -v v="$tV" -v i="$tI" 'BEGIN { exit !(v > i) }'; then
    ok "estacionalidad: verano ($diaV) ${tV}C > invierno ($diaI) ${tI}C"
  else
    mal "verano (${tV}C) no supera a invierno (${tI}C): revisa índice horario / zona horaria (12:00 UTC)"
  fi
else
  echo "  --   sin día de verano y/o invierno en el rango; salto estacionalidad"
fi

# 5) 6 municipios de control: corrección apreciable en interior de montaña
#    (Vistabella, Villahermosa, Morella), casi nula en la costa (Moncofa, Nules, Almenara).
if [ -n "$diaV" ]; then
  echo
  echo "-- Control (día de verano $diaV): celda vs altitud municipal, delta y T/HR --"
  table "select mun.nombre,
                round(m.elevacion_celda_m) celda_m,
                round(t.altitud_media_m)   altitud_m,
                round(m.delta_altitud_m)   delta_m,
                m.temp_12utc_c temp_c, m.hr_12utc_pct hr_pct
         from meteo_municipio m
         join municipio mun using (ine_code)
         join topografia_municipio t using (ine_code)
         where m.ine_code in ('12139','12130','12080','12077','12082','12011')
           and m.fecha = '$diaV'
         order by t.altitud_media_m desc"
fi

echo
if [ "$fallos" -eq 0 ]; then
  echo "TODO OK ($dias días x $munis municipios). Puedes lanzar el siguiente tramo."
else
  echo "$fallos comprobación(es) FALLARON. NO lances el siguiente tramo hasta resolverlo."
  exit 1
fi
