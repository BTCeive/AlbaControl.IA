#!/usr/bin/env bash
set -euo pipefail

LOG_DIR="artifacts_device/android_logs"
MAX_FILES="${MAX_FILES:-10}"

echo "=== Rotación de logs Android ==="
echo "Directorio: $LOG_DIR"
echo "Máximo permitido: $MAX_FILES archivos"

if [ ! -d "$LOG_DIR" ]; then
  echo "No existe el directorio de logs. Nada que rotar."
  exit 0
fi

# Contar archivos .log
COUNT=$(ls -1t "$LOG_DIR"/*.log 2>/dev/null | wc -l | tr -d ' ')
if [ "$COUNT" -le "$MAX_FILES" ]; then
  echo "Actualmente hay $COUNT archivos. No se necesita rotación."
  exit 0
fi

echo "Hay $COUNT archivos. Eliminando los más antiguos..."

# Mantener solo los últimos N
ls -1t "$LOG_DIR"/*.log | tail -n +$((MAX_FILES+1)) | while read -r OLD; do
  echo "Eliminando: $OLD"
  rm -f "$OLD"
done

echo "✅ Rotación completada."
