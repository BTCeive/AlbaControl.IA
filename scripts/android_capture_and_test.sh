#!/usr/bin/env bash
set -euo pipefail

APP_ID="${APP_ID:-com.example.albacontrol}"   # Ajusta si tu package es otro
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
LOG_DIR="artifacts_device/android_logs"
LOG_FILE="$LOG_DIR/run_$(date +%Y%m%d_%H%M%S).log"

echo "=== 1) Comprobando ADB y dispositivo ==="
if ! command -v adb >/dev/null 2>&1; then
   echo "ERROR: adb no encontrado. Instala platform-tools."
   exit 1
fi

adb devices
DEVICE_COUNT=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
if [ "$DEVICE_COUNT" -eq 0 ]; then
   echo "ERROR: No hay dispositivos en estado 'device'."
   echo "Activa depuración USB y acepta la clave RSA."
   exit 1
fi

echo
echo "=== 2) Compilando APK (assembleDebug) ==="
./gradlew assembleDebug

if [ ! -f "$APK_PATH" ]; then
   echo "ERROR: No se encontró $APK_PATH"
   exit 1
fi

echo
echo "=== 3) Instalando APK en el dispositivo ==="
adb install -r "$APK_PATH" || {
   echo "Si falla, prueba: adb uninstall $APP_ID"
   exit 1
}

echo
echo "=== 4) Preparando captura de logs ==="
mkdir -p "$LOG_DIR"
adb logcat -c

echo "Guardando logcat completo en:"
echo "  $LOG_FILE"

# Captura completa a archivo
( adb logcat -v time > "$LOG_FILE" ) &
LOGCAT_PID=$!

# Filtro útil en consola
( adb logcat -v time | grep -iE "AlbaControl|OCR|Tesseract|OpenCV|exception|error|fatal" ) &
FILTER_PID=$!

echo
echo "=== 5) Lanzando la app ==="
adb shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1

cat <<'EOF'

========================================================
✅ PRUEBA MANUAL EN EL DISPOSITIVO
========================================================

1) PRIMER DOCUMENTO (crea aprendizaje)
--------------------------------------
- Sube/captura un albarán real.
- Revisa el formulario autocompletado.
- Corrige campos incorrectos.
- Finaliza → la app enviará correcciones al backend.

Verifica en el servidor:
   curl -sS -H "X-API-Key: dev-key" http://127.0.0.1:5001/api/v1/corrections | jq .

Debe aparecer un nuevo archivo en:
   artifacts_device/persisted_corrections/

2) MISMO DOCUMENTO (validar aprendizaje)
----------------------------------------
- Sube el MISMO albarán otra vez.
- Observa si:
   ✅ Mejoran las posiciones de los campos  
   ✅ Menos errores OCR  
   ✅ Se aplica la plantilla del proveedor  
- Finaliza (si no hay cambios, no debe enviar correcciones)

3) Cuando termines, vuelve aquí y pulsa ENTER.
EOF

read -p "Pulsa ENTER cuando hayas terminado la prueba..."

echo
echo "=== 6) Deteniendo captura de logs ==="
kill "$LOGCAT_PID" 2>/dev/null || true
kill "$FILTER_PID" 2>/dev/null || true
sleep 1

echo
echo "=== 7) Resumen de logs capturados ==="
echo "Archivo completo:"
echo "  $LOG_FILE"
echo
echo "Últimas 100 líneas:"

#!/usr/bin/env bash
set -euo pipefail

APP_ID="${APP_ID:-com.example.albacontrol}"   # Ajusta si tu package es otro
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
LOG_DIR="artifacts_device/android_logs"
LOG_FILE="$LOG_DIR/run_$(date +%Y%m%d_%H%M%S).log"

echo "=== 1) Comprobando dispositivo conectado ==="
adb devices
DEVICE_COUNT=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
if [ "$DEVICE_COUNT" -eq 0 ]; then
   echo "ERROR: No hay dispositivos en estado 'device'. Activa depuración USB."
   exit 1
fi

echo
echo "=== 2) Compilando APK (assembleDebug) ==="
./gradlew assembleDebug

if [ ! -f "$APK_PATH" ]; then
   echo "ERROR: No se encontró $APK_PATH"
   exit 1
fi

echo
echo "=== 3) Instalando APK en el dispositivo ==="
adb install -r "$APK_PATH" || {
   echo "Si falla, prueba: adb uninstall $APP_ID"
   exit 1
}

echo
echo "=== 4) Preparando captura de logs ==="
mkdir -p "$LOG_DIR"
adb logcat -c

echo "Guardando logcat completo en:"
echo "  $LOG_FILE"

# Captura completa a archivo
( adb logcat -v time > "$LOG_FILE" ) &
LOGCAT_PID=$!

# Filtro útil en consola
( adb logcat -v time | grep -iE "AlbaControl|OCR|Tesseract|OpenCV|exception|error|fatal" ) &
FILTER_PID=$!

echo
echo "=== 5) Lanzando la app ==="
adb shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1

cat <<'EOF'

========================================================
✅ AHORA REALIZA LA PRUEBA EN EL DISPOSITIVO
========================================================

1) PRIMER DOCUMENTO (crea aprendizaje)
- Sube/captura un albarán real.
- Revisa el formulario autocompletado.
- Corrige campos incorrectos.
- Finaliza → se enviará la corrección al backend.

2) MISMO DOCUMENTO (validar aprendizaje)
- Sube el MISMO albarán otra vez.
- Observa si mejora la detección y el autocompletado.
- Finaliza (si no hay cambios, no debe enviar correcciones).

Cuando termines, vuelve aquí y pulsa ENTER.
EOF

read -p "Pulsa ENTER cuando hayas terminado la prueba..."

echo
echo "=== 6) Deteniendo captura de logs ==="
kill "$LOGCAT_PID" 2>/dev/null || true
kill "$FILTER_PID" 2>/dev/null || true
sleep 1

echo
echo "=== 7) Resumen de logs capturados ==="
echo "Archivo completo:"
echo "  $LOG_FILE"
echo
echo "Últimas 100 líneas:"
tail -n 100 "$LOG_FILE" || true

echo
echo "✅ Prueba completada. Revisa el archivo de logs para detectar errores o comportamientos inesperados."
