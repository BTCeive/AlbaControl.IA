#!/usr/bin/env bash
set -euo pipefail

# Ajusta si tu packageId es distinto
PACKAGE="com.albacontrol"
DEVICE=$(adb devices -l | sed -n '2p' | awk '{print $1}' || true)
TS=$(date +%Y%m%d_%H%M%S)
DIAG_DIR="artifacts_device/diagnostic_${TS}"
PULL_DIR="$DIAG_DIR/device_pull"
LOG_DIR="$DIAG_DIR/logs"
mkdir -p "$PULL_DIR" "$LOG_DIR"

if [ -z "$DEVICE" ]; then
  echo "ERROR: No hay dispositivo adb conectado. Conecta el dispositivo y vuelve a ejecutar."; exit 1
fi

echo "Device: $DEVICE"
echo "Output dir: $DIAG_DIR"

# 1) Buscar en rutas alternativas comunes y listar resultados
echo "=== Buscando en rutas alternativas (/sdcard, /sdcard/Download, /sdcard/Pictures, /data/local/tmp) ==="
adb -s "$DEVICE" shell "find /sdcard /sdcard/Download /sdcard/Pictures /data/local/tmp -maxdepth 4 -type f -name 'debug_*.json' -o -name 'tables_dump_latest.json' -o -name 'templates_debug' 2>/dev/null" | sed -n '1,200p' > "$DIAG_DIR/found_paths.txt" || true
echo "Rutas encontradas (primeras 200 líneas):"
sed -n '1,200p' "$DIAG_DIR/found_paths.txt" || true

# 2) Intentar pulls de ubicaciones alternativas y comunes
echo "=== Intentando pulls de rutas comunes y alternativas ==="
# rutas candidatas
CANDIDATES=(
  "/sdcard/Android/data/${PACKAGE}/files/templates_debug"
  "/sdcard/Android/data/${PACKAGE}/files/tables_dump_latest.json"
  "/sdcard/Download"
  "/sdcard/Pictures"
  "/data/local/tmp"
)
for r in "${CANDIDATES[@]}"; do
  echo "Pull intento: $r"
  adb -s "$DEVICE" pull "$r" "$PULL_DIR/" || true
done

# 3) Pull de cualquier ruta encontrada por find
while IFS= read -r remote; do
  [ -z "$remote" ] && continue
  fname=$(basename "$remote")
  echo "Pulling $remote -> $PULL_DIR/$fname"
  adb -s "$DEVICE" pull "$remote" "$PULL_DIR/$fname" || true
done < "$DIAG_DIR/found_paths.txt"

echo "Archivos descargados en $PULL_DIR:"
ls -lah "$PULL_DIR" || true

# 4) Listar contenido local de templates_debug (si existe)
LOCAL_TEMPLATES="artifacts_device/templates_debug/templates_debug"
echo "=== Listado local de templates_debug (si existe) ==="
if [ -d "$LOCAL_TEMPLATES" ]; then
  echo "Contenido de $LOCAL_TEMPLATES (primeras 200 líneas):"
  find "$LOCAL_TEMPLATES" -maxdepth 2 -type f -printf "%p %s bytes\n" | sed -n '1,200p' || true
else
  echo "No existe $LOCAL_TEMPLATES localmente."
fi

# 5) Mostrar contenido del tar diagnostic más reciente si existe
echo "=== Buscar tar diagnostic más reciente en artifacts_device ==="
LATEST_TAR=$(ls -t artifacts_device/diagnostic_*.tgz 2>/dev/null | head -n1 || true)
if [ -n "$LATEST_TAR" ]; then
  echo "Tar encontrado: $LATEST_TAR"
  echo "Listado del tar (primeras 200 líneas):"
  tar -tzf "$LATEST_TAR" | sed -n '1,200p' || true
else
  echo "No se encontró ningún diagnostic_*.tgz en artifacts_device."
fi

# 6) Crear patch con commits de la rama actual respecto a main (para aplicar en remoto)
BRANCH=$(git rev-parse --abbrev-ref HEAD)
echo "=== Creando patch de commits en rama $BRANCH respecto a origin/main (o main si origin no existe) ==="
git fetch origin main 2>/dev/null || true
BASE_REF="origin/main"
if ! git rev-parse --verify "$BASE_REF" >/dev/null 2>&1; then
  BASE_REF="main"
fi
PATCH_OUT="../${BRANCH}_changes_$(date +%Y%m%d_%H%M%S).patch"
git format-patch "$BASE_REF" --stdout > "$PATCH_OUT" || true
echo "Patch creado: $PATCH_OUT"
ls -lh "$PATCH_OUT" || true

# 7) (Opcional) Preparar push — descomenta y establece REMOTE_URL si quieres empujar desde aquí
: <<'PUSH_BLOCK'
# REMOTE_URL="git@github.com:usuario/repo.git"  # <- reemplaza con tu URL

PUSH_BLOCK
