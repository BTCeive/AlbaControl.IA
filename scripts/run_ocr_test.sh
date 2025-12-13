#!/usr/bin/env bash
set -euo pipefail

APP_ID="${APP_ID:-com.example.albacontrol}"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
OUT_DIR="artifacts_device/ocr_test"
PULLED_JSON="$OUT_DIR/ocr_result.json"

mkdir -p "$OUT_DIR"

echo "=== 1) Compilar APK (assembleDebug)"
./gradlew assembleDebug

if [ ! -f "$APK_PATH" ]; then
  echo "ERROR: APK no encontrado en $APK_PATH"
  exit 1
fi

echo "=== 2) Instalar APK en dispositivo"
adb install -r "$APK_PATH" || { echo "Instalación fallida. Prueba adb uninstall $APP_ID"; exit 1; }

echo "=== 3) Lanzar TestOcrActivity"
adb shell am start -n "$APP_ID/com.example.albacontrol.learning.TestOcrActivity" || {
  echo "Error lanzando la actividad. Comprueba el package y el nombre de la actividad."
  exit 1
}

echo "=== 4) Esperando 3s para que la actividad termine y guarde el JSON"
sleep 3

echo "=== 5) Copiando ocr_result.json desde el dispositivo"
adb shell run-as "$APP_ID" cat files/ocr_result.json > "$PULLED_JSON" 2>/dev/null || {
  echo "Intentando alternativa con adb pull (requiere permisos root o path accesible)..."
  adb pull /data/data/"$APP_ID"/files/ocr_result.json "$PULLED_JSON" || true
}

if [ -f "$PULLED_JSON" ]; then
  echo "✅ OCR JSON extraído a: $PULLED_JSON"
  echo "Contenido (primeras 200 líneas):"
  sed -n '1,200p' "$PULLED_JSON" || true
else
  echo "⚠️ No se pudo extraer ocr_result.json. Revisa permisos y logs."
fi
