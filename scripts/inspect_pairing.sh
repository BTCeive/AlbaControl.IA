#!/usr/bin/env bash
set -euo pipefail

BASE_DIR=$(ls -td artifacts_device/tess_debug_run_* 2>/dev/null | head -n1 || true)
if [ -z "$BASE_DIR" ]; then
  echo "No se encontró artifacts_device/tess_debug_run_*"; exit 1
fi
PSM_DIR="$BASE_DIR/variants/psm6"
TEMPLATES_DIR="artifacts_device/templates_debug/templates_debug"
OUT_DIR="artifacts_device/inspect_pairing"
mkdir -p "$OUT_DIR"

echo "Base dir: $BASE_DIR"
echo "PSM dir: $PSM_DIR"
echo

# 1) Conteos y listado resumido
echo "==> Conteos en psm6"
total=$(find "$PSM_DIR" -maxdepth 1 -type f -name '*.txt' | wc -l || true)
nonempty=$(find "$PSM_DIR" -maxdepth 1 -type f -name '*.txt' -size +0c | wc -l || true)
echo "psm6: total_txt=$total nonempty_txt=$nonempty"
echo

# 2) Mostrar hasta 3 .txt no vacíos (muestras)
echo "==> Muestras de .txt no vacíos (hasta 3)"
find "$PSM_DIR" -maxdepth 1 -type f -name '*.txt' -size +0c | head -n3 | while read -r f; do
  echo "Archivo: $f"
  sed -n '1,60p' "$f"
  echo "----"
done || true
echo

# 3) Listado completo de .txt en psm6 (primeras 200 líneas)
echo "==> Listado completo psm6 (primeras 200 líneas)"
find "$PSM_DIR" -maxdepth 1 -type f -name '*.txt' -printf "%p %s bytes\n" | sed -n '1,200p' || true
echo

# 4) Emparejado por prefijo y por substring 'preproc'; copiar matches a OUT_DIR
echo "==> Emparejado prefijo y substring 'preproc' (copiando hasta 2 matches por debug file)"
> "$OUT_DIR/unmatched.txt"
for df in "$TEMPLATES_DIR"/debug_*.json; do
  [ -f "$df" ] || continue
  base=$(basename "$df")
  prefix="${base%.*}"
  echo "Debug: $base"
  # prefijo
  matches_pref=$(find "$PSM_DIR" -maxdepth 1 -type f -name "${prefix}*.txt" -print | sed -n '1,10p' || true)
  if [ -n "$matches_pref" ]; then
    echo "  Pref matches:"
    echo "$matches_pref" | sed -n '1,5p'
    echo "$matches_pref" | head -n2 | xargs -I{} cp -v {} "$OUT_DIR/" 2>/dev/null || true
    continue
  fi
  # substring preproc fallback
  matches_sub=$(find "$PSM_DIR" -maxdepth 1 -type f -iname "*preproc*${prefix#debug_*}*.txt" -print | sed -n '1,10p' || true)
  if [ -n "$matches_sub" ]; then
    echo "  Substring matches:"
    echo "$matches_sub" | sed -n '1,5p'
    echo "$matches_sub" | head -n2 | xargs -I{} cp -v {} "$OUT_DIR/" 2>/dev/null || true
    continue
  fi
  echo "  No matches found for $base"
  echo "$base" >> "$OUT_DIR/unmatched.txt"
done

echo
echo "Emparejado completado. Muestras copiadas a: $OUT_DIR"
echo "Listado de debug files sin match guardado en: $OUT_DIR/unmatched.txt (si existe)"
echo
echo "Si quieres que reintente emparejado con heurística adicional o que aplique preprocesado OpenCV y reejecute Tesseract, responde 'reintentar emparejado' o 'preprocesar y reejecutar'."
