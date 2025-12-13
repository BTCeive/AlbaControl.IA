#!/usr/bin/env bash
set -euo pipefail

APK=app/build/outputs/apk/debug/app-debug.apk
if [ ! -f "$APK" ]; then
  echo "ERROR: APK no encontrado en $APK"
  exit 1
fi

echo "1) Comprobando dispositivos ADB..."
adb devices -l || true

DEVCOUNT=$(adb devices | sed 1d | sed '/^$/d' | wc -l)
if [ "$DEVCOUNT" -gt 0 ]; then
  echo "-> Dispositivo(s) conectados: $DEVCOUNT"
else
  echo "-> No hay dispositivos conectados. Intentaré listar AVDs..."
  if command -v emulator >/dev/null 2>&1; then
    AVD=$(emulator -list-avds | sed -n '1p' || true)
    if [ -n "$AVD" ]; then
      echo "-> AVD encontrado: $AVD — arrancando emulador en segundo plano"
      emulator -avd "$AVD" -no-window -no-audio >/dev/null 2>&1 &
      EMU_PID=$!
      echo "  PID emulador: $EMU_PID"
      echo "  Esperando hasta 120s a que el emulador se conecte..."
      SECONDS=0
      while [ $SECONDS -lt 120 ]; do
        sleep 3
        if [ $(adb devices | sed 1d | sed '/^$/d' | wc -l) -gt 0 ]; then
          echo "  Emulador conectado"
          break
        fi
        SECONDS=$((SECONDS+3))
      done
      if [ $SECONDS -ge 120 ]; then
        echo "  Timeout esperando el emulador (120s)." >&2
      fi
    else
      echo "-> No se encontraron AVDs instalados (no puedo arrancar emulador)." >&2
    fi
  else
    echo "-> Comando 'emulator' no disponible en PATH; no puedo arrancar AVD." >&2
  fi
fi

echo
echo "2) Volviendo a comprobar dispositivos ADB (espera breve)..."
sleep 1
adb devices -l || true
DEVCOUNT2=$(adb devices | sed 1d | sed '/^$/d' | wc -l)
if [ "$DEVCOUNT2" -eq 0 ]; then
  echo "ERROR: No hay dispositivos disponibles. Conecta un dispositivo o inicia un emulador y reintenta." >&2
  exit 2
fi

# Seleccionar primer dispositivo (serial)
SERIAL=$(adb devices | sed 1d | sed '/^$/d' | awk '{print $1; exit}')
if [ -z "$SERIAL" ]; then
  echo "ERROR: no pude determinar el serial del dispositivo." >&2
  exit 3
fi

echo "3) Instalando APK en dispositivo $SERIAL"
adb -s "$SERIAL" install -r "$APK" || { echo "adb install falló" >&2; exit 4; }

echo "4) Lanzando TestOcrActivity"
adb -s "$SERIAL" shell am start -n "com.example.albacontrol/com.example.albacontrol.learning.TestOcrActivity" || {
  echo "Error lanzando la actividad con am start" >&2; exit 5; }

echo "5) Esperando 3s para que la actividad termine"
sleep 3

OUT_DIR=artifacts_device/ocr_test
mkdir -p "$OUT_DIR"
PULLED="$OUT_DIR/ocr_result.json"

echo "6) Intentando extraer files/ocr_result.json usando run-as"
adb -s "$SERIAL" shell run-as com.example.albacontrol cat files/ocr_result.json > "$PULLED" 2>/dev/null || {
  echo "  run-as falló, intentando adb pull (puede requerir root)" >&2
  adb -s "$SERIAL" pull /data/data/com.example.albacontrol/files/ocr_result.json "$PULLED" || true
}

if [ -f "$PULLED" ]; then
  echo "✅ OCR JSON extraído a: $PULLED"
  sed -n '1,200p' "$PULLED" || true
else
  echo "⚠️ No se pudo extraer ocr_result.json. Revisa permisos o logs." >&2
  exit 6
fi
