#!/usr/bin/env bash
set -euo pipefail

APP_ID="${APP_ID:-com.example.albacontrol}"
LOG_DIR="artifacts_device/android_logs"
LOG_FILE="$LOG_DIR/onlylog_$(date +%Y%m%d_%H%M%S).log"

echo "=== Captura rápida de logs para $APP_ID ==="

if ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: adb no encontrado."
  exit 1
fi

adb devices
DEVICE_COUNT=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
if [ "$DEVICE_COUNT" -eq 0 ]; then
  echo "ERROR: No hay dispositivos en estado 'device'."
  exit 1
fi

mkdir -p "$LOG_DIR"
adb logcat -c

echo "Guardando logcat completo en:"
echo "  $LOG_FILE"

# Captura completa
( adb logcat -v time > "$LOG_FILE" ) &
LOGCAT_PID=$!

# Filtro útil en consola
( adb logcat -v time | grep -iE "AlbaControl|OCR|Tesseract|OpenCV|exception|error|fatal" ) &
FILTER_PID=$!

cat <<'MSG'

========================================================
✅ Capturando logs en tiempo real
========================================================
- Usa la app normalmente.
- Observa errores, OCR, cámara, red, etc.
- Cuando termines, vuelve aquí y pulsa ENTER.
MSG

read -p "Pulsa ENTER para detener la captura..."

kill "$LOGCAT_PID" 2>/dev/null || true
kill "$FILTER_PID" 2>/dev/null || true

echo
echo "=== Últimas 80 líneas del log ==="
tail -n 80 "$LOG_FILE" || true

echo
echo "✅ Logs guardados en:"
echo "  $LOG_FILE"
