#!/usr/bin/env bash
set -euo pipefail

# Ajusta si tu estructura difiere
BASE_DIR=$(ls -td artifacts_device/tess_debug_run_* 2>/dev/null | head -n1 || true)
if [ -z "$BASE_DIR" ]; then
  echo "No se encontró artifacts_device/tess_debug_run_*"; exit 1
fi
VAR_DIR="$BASE_DIR/variants"
TEMPLATES_DIR="artifacts_device/templates_debug/templates_debug"
OUT_DIR="$BASE_DIR/inspect_txt"
mkdir -p "$OUT_DIR"

echo "Base dir: $BASE_DIR"
echo

# 1) Listar variantes y contar .txt generados
echo "==> Variants and txt counts"
for v in "$VAR_DIR"/*; do
  [ -d "$v" ] || continue
  total=$(find "$v" -maxdepth 1 -type f -name '*.txt' | wc -l)
  nonempty=$(find "$v" -maxdepth 1 -type f -name '*.txt' -size +0c | wc -l)
  echo "$(basename "$v"): total_txt=$total nonempty_txt=$nonempty"
done
echo

# 2) Mostrar primer .txt no vacío por variante y su contenido (hasta 80 líneas)
echo "==> Primer .txt no vacío por variante (muestra)"
for v in "$VAR_DIR"/*; do
  [ -d "$v" ] || continue
  sample=$(find "$v" -maxdepth 1 -type f -name '*.txt' -size +0c | head -n1 || true)
  if [ -n "$sample" ]; then
    echo "Variant: $(basename "$v") -> $sample"
    echo "---- start ----"
    sed -n '1,80p' "$sample"
    echo "---- end ----"
  else
    echo "Variant: $(basename "$v") -> (no non-empty txt found)"
  fi
  echo
done

echo
# 3) Mostrar lista completa de .txt por variante (primeras 200 líneas)
echo "==> Listado completo de .txt por variante (primeras 200 líneas)"
for v in "$VAR_DIR"/*; do
  [ -d "$v" ] || continue
  echo "Variant: $(basename "$v")"
  find "$v" -maxdepth 1 -type f -name '*.txt' -printf "%p %s bytes\n" | sed -n '1,200p' || true
  echo
done

echo
# 4) Intentar emparejar debug_*.json con .txt por prefijo y mostrar resultados
echo "==> Emparejado heurístico debug -> txt (prefijo) y conteos"
for df in "$TEMPLATES_DIR"/debug_*.json; do
  [ -f "$df" ] || continue
  base=$(basename "$df")
  prefix="${base%.*}"
  echo "Debug file: $base"
  # buscar txts que comiencen con el mismo prefijo en todas variantes
  matches=$(find "$VAR_DIR" -type f -name "${prefix}*.txt" -print | sed -n '1,200p' || true)
  if [ -n "$matches" ]; then
    echo "  Matches found:"
    echo "$matches" | sed -n '1,50p'
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
# 5) Guardar resumen y finalizar
echo "Resumen guardado en: $OUT_DIR"
echo "Si quieres que vuelva a ejecutar Tesseract sobre los archivos no emparejados con otro heurístico, responde 'reintentar emparejado' o 'preprocesar y reejecutar'."
