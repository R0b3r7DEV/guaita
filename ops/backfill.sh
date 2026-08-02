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
#   bash ops/backfill.sh espera                              # espera y da el veredicto
#
set -euo pipefail

CONTAINER="guaita-backfill"
REPORTS_DIR="etl/reports"
STAMP_FILE="$REPORTS_DIR/.last-tramo-end"
RC_FILE="$REPORTS_DIR/.last-tramo-rc"
MIN_INTERVAL="${GUAITA_BACKFILL_MIN_INTERVAL_SECS:-3600}"
COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.vps.yml)

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

tramo() {
  local desde="${1:?uso: tramo <desde> <hasta> [finalize]}"
  local hasta="${2:?uso: tramo <desde> <hasta> [finalize]}"
  local fin="${3:-false}"

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

  local log="$REPORTS_DIR/backfill-$desde-$hasta-$(date +%Y%m%d-%H%M).log"
  echo "lanzando tramo $desde-$hasta (finalize=$fin) -> $log"
  # Trabajo desatendido: nos reinvocamos con el subcomando interno _run bajo nohup.
  # Así el JSON no viaja entre comillas anidadas y el fin/rc se registran siempre.
  nohup bash "$0" _run "$desde" "$hasta" "$fin" "$log" >/dev/null 2>&1 &
  echo "PID $!. Sigue con:  bash ops/backfill.sh espera   (o  tail -f $log)"
}

# Interno (lo lanza `tramo` bajo nohup): corre un tramo síncrono y registra rc + fin.
_run() {
  local desde=$1 hasta=$2 fin=$3 log=$4
  export SPRING_APPLICATION_JSON="{\"guaita.backfill.run\":true,\"guaita.backfill.from\":$desde,\"guaita.backfill.to\":$hasta,\"guaita.backfill.finalize\":$fin,\"guaita.backfill.report-path\":\"/out/fwi-backfill.md\"}"
  local rc=0
  "${COMPOSE[@]}" run --rm --name "$CONTAINER" \
    -e SPRING_APPLICATION_JSON \
    -v "$PWD/$REPORTS_DIR:/out" \
    api >"$log" 2>&1 || rc=$?
  echo "$rc" >"$RC_FILE"
  date +%s >"$STAMP_FILE"
  return "$rc"
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
  espera)
    espera
    ;;
  _run)
    shift
    _run "$@"
    ;;
  *)
    echo "uso: bash ops/backfill.sh {tramo <desde> <hasta> [finalize] | espera}" >&2
    exit 2
    ;;
esac
