#!/usr/bin/env bash
# Orquestación del backfill histórico en el VPS (README, "Despliegue en VPS").
# Corre en el HOST (no en un contenedor): son comandos docker compose.
#
# Salvaguardas (la máquina comparte producción con XPL0DAY):
#   - UN SOLO tramo a la vez: lanzar otro con uno vivo se rechaza.
#   - `espera` bloquea hasta que el tramo en curso acabe e informa si fue bien.
#   - intervalo mínimo entre tramos (el límite horario de Open-Meteo: dos
#     tramos seguidos se suman en la misma ventana y dan 429).
#
# Uso (desde la raíz del repo):
#   bash ops/backfill.sh tramo <desde> <hasta> [finalize]   # lanza desatendido
#   bash ops/backfill.sh tramo-dry                          # ENSAYO: 1 año x 5 municipios
#   bash ops/backfill.sh dry-limpia                         # borra lo del ensayo
#   bash ops/backfill.sh espera                             # espera y da el veredicto
#
set -euo pipefail

CONTAINER="guaita-backfill"
REPORTS_DIR="etl/reports"
STAMP_FILE="$REPORTS_DIR/.last-tramo-end"
RC_FILE="$REPORTS_DIR/.last-tramo-rc"
MIN_INTERVAL="${GUAITA_BACKFILL_MIN_INTERVAL_SECS:-3600}"
MEM_WARN_MIB="${GUAITA_MEM_WARN_MIB:-1150}"
# Presupuesto DIARIO de peticiones-año a Open-Meteo (límite por IP, reset 00:00 UTC; sin cabeceras
# de consumo, así que lo contamos nosotros). Un 429 así es imposible por construcción, no por
# disciplina. Medido: ~7 peticiones-año/día tumbaron la IP; 6 deja margen.
DAILY_BUDGET="${GUAITA_DAILY_BUDGET:-6}"
COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.vps.yml)

# Docker rootless: el CLI usa el contexto persistido, pero fijamos XDG_RUNTIME_DIR por si el
# script se relanza (nohup) en un entorno sin él.
: "${XDG_RUNTIME_DIR:=/run/user/$(id -u)}"
export XDG_RUNTIME_DIR

# Ensayo en seco: 5 municipios (5 de los 6 de control: 3 de interior de montaña
# con corrección altitudinal apreciable + 2 costeros con delta ~0) y un año.
DRY_INES="12139,12130,12080,12077,12011"
DRY_YEAR="${GUAITA_DRY_YEAR:-2020}"

[ -f docker-compose.yml ] || {
  echo "ejecuta desde la raíz del repo (no encuentro docker-compose.yml)" >&2
  exit 2
}
mkdir -p "$REPORTS_DIR"

# 0 (éxito) si hay un contenedor de backfill vivo. Nombre exacto (línea completa).
hay_tramo() { docker ps --format '{{.Names}}' | grep -Fxq "$CONTAINER"; }

mostrar_rc() {
  [ -f "$RC_FILE" ] || {
    echo "sin registro de tramos previos."
    return 0
  }
  local rc
  rc="$(cat "$RC_FILE")"
  if [ "$rc" = "0" ]; then
    echo "último tramo: OK (rc=0)."
  else
    echo "último tramo: FALLÓ (rc=$rc). Revisa el log en $REPORTS_DIR/ y RELÁNZALO (es idempotente)."
  fi
}

# Guardas comunes + lanzamiento desatendido. Args: desde hasta finalize ine_codes
_lanza() {
  local desde=$1 hasta=$2 fin=$3 ines=$4

  # 1. Concurrencia: nunca dos tramos a la vez (el 429 seguro).
  if hay_tramo; then
    echo "ABORTA: ya hay un tramo corriendo ($CONTAINER)." >&2
    echo "Espéralo con:  bash ops/backfill.sh espera" >&2
    exit 1
  fi

  # 2. Intervalo: si el anterior acabó hace menos del mínimo, pide confirmación.
  if [ -f "$STAMP_FILE" ]; then
    local last now delta
    last="$(cat "$STAMP_FILE")"
    now="$(date +%s)"
    delta=$((now - last))
    if [ "$delta" -lt "$MIN_INTERVAL" ]; then
      echo "AVISO: el tramo anterior acabó hace $((delta / 60)) min (< $((MIN_INTERVAL / 60)) min)." >&2
      echo "Lanzar ahora arriesga un 429 de Open-Meteo (límite horario)." >&2
      read -r -p "¿Lanzar igualmente? escribe 'si': " ans || ans=""
      [ "$ans" = "si" ] || {
        echo "cancelado."
        exit 1
      }
    fi
  fi

  # 3. Presupuesto diario de Open-Meteo (por IP, reset 00:00 UTC). Reserva ANTES de lanzar
  #    (pesimista: cuenta el tramo entero aunque un 429 lo corte a medias, porque igual consumió).
  local budget_file today span
  budget_file="$REPORTS_DIR/.budget-$(date -u +%F)"
  today=$(cat "$budget_file" 2>/dev/null || echo 0)
  span=$((hasta - desde + 1))
  if [ $((today + span)) -gt "$DAILY_BUDGET" ]; then
    echo "ABORTA: presupuesto diario de Open-Meteo. Hoy (UTC $(date -u +%F)) van $today peticiones-año;" >&2
    echo "este tramo pide $span y el techo es $DAILY_BUDGET. El límite DIARIO resetea a las 00:00 UTC:" >&2
    echo "reintenta mañana, o sube GUAITA_DAILY_BUDGET si mides que hay margen." >&2
    exit 1
  fi
  echo $((today + span)) >"$budget_file"
  echo "presupuesto: hoy van $((today + span))/$DAILY_BUDGET peticiones-año (UTC $(date -u +%F))"

  # Finalize: el contenedor (rootless, usuario no-root) escribe el informe en el bind-mount. Bajo
  # rootless su uid no es dueño de $REPORTS_DIR -> AccessDenied. El script corre como el dueño, así
  # que deja el dir escribible y retira un informe viejo de otro propietario para que lo cree limpio.
  if [ "$fin" = "true" ]; then
    chmod 777 "$REPORTS_DIR" 2>/dev/null || true
    rm -f "$REPORTS_DIR/fwi-backfill.md"
  fi

  local log="$REPORTS_DIR/backfill-$desde-$hasta-$(date +%Y%m%d-%H%M).log"
  echo "lanzando tramo $desde-$hasta (finalize=$fin, ine_codes='${ines:-todos}') -> $log"
  # Trabajo desatendido: nos reinvocamos con el subcomando interno _run bajo nohup.
  # Así el JSON no viaja entre comillas anidadas y el fin/rc se registran siempre.
  nohup bash "$0" _run "$desde" "$hasta" "$fin" "$log" "$ines" >/dev/null 2>&1 &
  echo "PID $!. Sigue con:  bash ops/backfill.sh espera   (o  tail -f $log)"
  # Monitor de memoria automático en segundo plano (espera activa + pico a fichero + aviso).
  nohup bash "$0" monitor >/dev/null 2>&1 &
  echo "monitor de memoria lanzado en segundo plano (etl/reports/mem-*.log)"
}

tramo() {
  local desde="${1:?uso: tramo <desde> <hasta> [finalize]}"
  local hasta="${2:?uso: tramo <desde> <hasta> [finalize]}"
  _lanza "$desde" "$hasta" "${3:-false}" ""
}

tramo_dry() {
  echo "ENSAYO EN SECO: año $DRY_YEAR, municipios $DRY_INES (5 x 1 año, finalize=false)."
  echo "Prueba la mecánica completa sin comprometer la serie. Deshacer: bash ops/backfill.sh dry-limpia"
  _lanza "$DRY_YEAR" "$DRY_YEAR" "false" "$DRY_INES"
}

# Interno (lo lanza _lanza bajo nohup): corre un tramo síncrono y registra rc + fin.
_run() {
  local desde=$1 hasta=$2 fin=$3 log=$4 ines=${5:-}
  export SPRING_APPLICATION_JSON="{\"guaita.backfill.run\":true,\"guaita.backfill.from\":$desde,\"guaita.backfill.to\":$hasta,\"guaita.backfill.finalize\":$fin,\"guaita.backfill.ine-codes\":\"$ines\",\"guaita.backfill.report-path\":\"/out/fwi-backfill.md\"}"
  local rc=0
  "${COMPOSE[@]}" run --rm --name "$CONTAINER" \
    -e SPRING_APPLICATION_JSON \
    -v "$PWD/$REPORTS_DIR:/out" \
    api >"$log" 2>&1 || rc=$?
  echo "$rc" >"$RC_FILE"
  date +%s >"$STAMP_FILE"
  return "$rc"
}

# Deshace el ensayo: borra FWI y meteo de los 5 municipios para el año del ensayo.
dry_limpia() {
  local u d
  u="$("${COMPOSE[@]}" exec -T db printenv POSTGRES_USER | tr -d '\r\n')"
  d="$("${COMPOSE[@]}" exec -T db printenv POSTGRES_DB | tr -d '\r\n')"
  local ines_sql
  ines_sql="'$(echo "$DRY_INES" | sed "s/,/','/g")'"
  echo "borrando FWI y meteo del ensayo ($DRY_INES, año $DRY_YEAR)..."
  "${COMPOSE[@]}" exec -T db psql -U "$u" -d "$d" -v ON_ERROR_STOP=1 -c "
    delete from fwi_municipio
      where ine_code in ($ines_sql) and extract(year from fecha) = $DRY_YEAR;
    delete from meteo_municipio
      where ine_code in ($ines_sql) and extract(year from fecha) = $DRY_YEAR;"
  echo "limpio."
}

# MiB de una cadena tipo "725.3MiB" o "1.2GiB" (primer campo de docker stats MemUsage).
_a_mib() {
  awk '{u=$1; if (u ~ /GiB/){gsub(/GiB/,"",u); print u*1024} else {gsub(/[A-Za-z]/,"",u); print u+0}}'
}

# Vigila la memoria del contenedor del tramo: ESPERA ACTIVA a que exista (arregla la carrera de
# comprobar antes de tiempo), muestrea cada 4 s durante toda su vida, registra cada muestra y el
# PICO en un fichero, y avisa si una muestra supera el umbral. Pensada para lanzarse en segundo
# plano junto al tramo, sin intervención. Umbral: GUAITA_MEM_WARN_MIB (1150 por defecto).
monitor() {
  local memlog="$REPORTS_DIR/mem-$(date +%Y%m%d-%H%M%S).log"
  echo "monitor de memoria -> $memlog (aviso si supera ${MEM_WARN_MIB} MiB)"
  local i max=0 val
  for i in $(seq 1 60); do # espera hasta ~4 min a que arranque el contenedor
    hay_tramo && break
    sleep 4
  done
  if ! hay_tramo; then
    echo "monitor: el contenedor no apareció; nada que medir" | tee -a "$memlog"
    return 0
  fi
  while hay_tramo; do
    val=$(docker stats --no-stream --format '{{.MemUsage}}' "$CONTAINER" 2>/dev/null | _a_mib)
    if [ -n "$val" ]; then
      max=$(awk -v a="$max" -v b="$val" 'BEGIN{print (b>a)?b:a}')
      printf '%s  %s MiB\n' "$(date +%T)" "$val" >>"$memlog"
      if awk -v v="$val" -v t="$MEM_WARN_MIB" 'BEGIN{exit !(v>t)}'; then
        printf '%s  WARN: %s MiB > umbral %s MiB\n' "$(date +%T)" "$val" "$MEM_WARN_MIB" >>"$memlog"
      fi
    fi
    sleep 4
  done
  echo "PICO_MiB=$max" >>"$memlog"
  echo "monitor: pico observado ${max} MiB (detalle en $memlog)"
}

espera() {
  if ! hay_tramo; then
    echo "no hay ningún tramo corriendo."
    mostrar_rc
    return 0
  fi
  echo "esperando a que termine el tramo ($CONTAINER)..."
  while hay_tramo; do sleep 10; done
  sleep 2 # deja que _run escriba rc/fin
  mostrar_rc
}

case "${1:-}" in
  tramo)
    shift
    tramo "$@"
    ;;
  tramo-dry)
    tramo_dry
    ;;
  dry-limpia)
    dry_limpia
    ;;
  espera)
    espera
    ;;
  monitor)
    monitor
    ;;
  _run)
    shift
    _run "$@"
    ;;
  *)
    echo "uso: bash ops/backfill.sh {tramo <desde> <hasta> [finalize] | tramo-dry | dry-limpia | espera | monitor}" >&2
    exit 2
    ;;
esac
