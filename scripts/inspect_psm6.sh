#!/usr/bin/env bash
set -euo pipefail

# Ajusta si tu estructura difiere
BASE_DIR=$(ls -td artifacts_device/tess_debug_run_* 2>/dev/null | head -n1 || true)
if [ -z "$BASE_DIR" ]; then
  echo "No se encontró artifacts_device/tess_debug_run_*"; exit 1
fi
VAR_DIR="$BASE_DIR/variants"
PSM_DIR="$VAR_DIR/psm6"
TEMPLATES_DIR="artifacts_device/templates_debug/templates_debug"
OUT_DIR="artifacts_device/inspect_samples"
mkdir -p "$OUT_DIR"

echo "Base dir: $BASE_DIR"
echo "PSM dir: $PSM_DIR"
echo

# 1) Conteos y listado
echo "==> Conteos en psm6"
total=$(find "$PSM_DIR" -maxdepth 1 -type f -name '*.txt' | wc -l || true)
nonempty=$(find "$PSM_DIR" -maxdepth 1 -type f -name '*.txt' -size +0c | wc -l || true)
echo "psm6: total_txt=$total nonempty_txt=$nonempty"
echo

# 2) Primer .txt no vacío y su contenido
echo "==> Primer .txt no vacío (psm6)"
first_nonempty=$(find "$PSM_DIR" -maxdepth 1 -type f -name '*.txt' -size +0c | head -n1 || true)
if [ -n "$first_nonempty" ]; then
  echo "Archivo: $first_nonempty"
  echo "---- inicio ----"
  sed -n '1,120p' "$first_nonempty"
  echo "---- fin ----"
  cp "$first_nonempty" "$OUT_DIR/" || true
else
  echo "(No se encontró .txt no vacío en psm6)"
fi
echo

# 3) Listado completo de .txt en psm6 (primeras 200 líneas)
echo "==> Listado completo psm6 (primeras 200 líneas)"
find "$PSM_DIR" -maxdepth 1 -type f -name '*.txt' -printf "%p %s bytes\n" | sed -n '1,200p' || true
echo

# 4) Emparejado heurístico por prefijo con debug_*.json
echo "==> Emparejado heurístico debug -> txt (prefijo)"
for df in "$TEMPLATES_DIR"/debug_*.json; do
  [ -f "$df" ] || continue
  base=$(basename "$df")
  prefix="${base%.*}"
  echo "Debug file: $base"
  # buscar txts que comiencen con el mismo prefijo en psm6
  matches=$(find "$PSM_DIR" -maxdepth 1 -type f -name "${prefix}*.txt" -print | sed -n '1,50p' || true)
  if [ -n "$matches" ]; then
    echo "  Matches found:"
    echo "$matches" | sed -n '1,10p'
    # copiar hasta 2 matches para inspección
    echo "$matches" | head -n2 | xargs -I{} cp -v {} "$OUT_DIR/" 2>/dev/null || true
    # mostrar primer match no vacío si existe
    first_nonempty=$(echo "$matches" | while read -r m; do [ -s "$m" ] && { echo "$m"; break; }; done || true)
    if [ -n "$first_nonempty" ]; then
      echo "  Primer match no vacío: $first_nonempty"
      echo "  Contenido (primeras 40 líneas):"
      sed -n '1,40p' "$first_nonempty"
    else
      echo "  No hay matches no vacíos para este debug file"
    fi
  else
    echo "  No se encontraron matches por prefijo para $base"
  fi
  echo
done

echo
# 5) Resumen y ubicación de muestras
echo "==> Resumen"
echo "Muestras copiadas a: $OUT_DIR (si existen)"
echo "Si quieres que pruebe un emparejado alternativo (buscar por substring 'preproc' o por fecha/timestamp), responde 'reintentar emparejado'."
