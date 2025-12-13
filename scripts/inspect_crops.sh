#!/usr/bin/env bash
set -euo pipefail

BASE_DIR=$(find artifacts_device -maxdepth 1 -type d -name 'tess_debug_run_*' -print0 | xargs -r -0 ls -td | head -n1)
if [ -z "$BASE_DIR" ]; then echo "No se encontró artifacts_device/tess_debug_run_*"; exit 1; fi
VAR_DIR="$BASE_DIR/variants"
TEMPLATES_CROPS="artifacts_device/templates_debug/templates_debug/crops"
OUT_DIR="$BASE_DIR/inspect"
mkdir -p "$OUT_DIR"

echo "Base dir: $BASE_DIR"
echo

# 1) Listar variantes y contar .txt generados
echo "==> Variants and txt counts"
for v in "$VAR_DIR"/*; do
  [ -d "$v" ] || continue
  cnt_all=$(find "$v" -maxdepth 1 -type f -name '*.txt' | wc -l)
  cnt_nonempty=$(find "$v" -maxdepth 1 -type f -name '*.txt' -size +0c | wc -l)
  echo "$(basename "$v"): total_txt=$cnt_all nonempty_txt=$cnt_nonempty"
done
echo

# 2) Mostrar primer .txt no vacío encontrado (por variante)
echo "==> Primeras muestras de .txt no vacías (por variante)"
for v in "$VAR_DIR"/*; do
  [ -d "$v" ] || continue
  sample=$(find "$v" -maxdepth 1 -type f -name '*.txt' -size +0c | head -n1 || true)
  if [ -n "$sample" ]; then
    echo "Variant: $(basename "$v") -> $sample"
    echo "---- start ----"
    sed -n '1,120p' "$sample"
    echo "---- end ----"
  else
    echo "Variant: $(basename "$v") -> (no non-empty txt found)"
  fi
  echo
done

echo
# 3) Listar crops y mostrar info de un crop de ejemplo
echo "==> Listado crops (primeras 200 entradas)"
find "$TEMPLATES_CROPS" -maxdepth 1 -type f -iname '*.png' -o -iname '*.jpg' -o -iname '*.jpeg' -printf "%p %s bytes\n" | sed -n '1,200p' || true
echo

EXAMPLE_IMG=$(find "$TEMPLATES_CROPS" -type f -iname '*.png' -o -iname '*.jpg' -o -iname '*.jpeg' | head -n1 || true)
if [ -n "$EXAMPLE_IMG" ]; then
  echo "Ejemplo crop: $EXAMPLE_IMG"
  ls -lh "$EXAMPLE_IMG"
  if command -v identify >/dev/null 2>&1; then
    identify -format "Format: %m  Dimensions: %wx%h  Depth: %z\n" "$EXAMPLE_IMG" || true
  else
    echo "identify (ImageMagick) no disponible; mostrando file info:"
    file "$EXAMPLE_IMG" || true
  fi
  # crear miniatura base64 para inspección rápida (no imprime todo)
  PREVIEW="$OUT_DIR/preview_$(basename "$EXAMPLE_IMG").b64"
  base64 "$EXAMPLE_IMG" > "$PREVIEW" || true
  echo "Miniatura base64 guardada en: $PREVIEW"
else
  echo "No se encontró ningún crop en $TEMPLATES_CROPS"
fi
echo

# 4) Contar crops por tamaño y detectar posibles 0-byte o formatos raros
echo "==> Estadísticas crops"
find "$TEMPLATES_CROPS" -type f -iname '*.png' -o -iname '*.jpg' -o -iname '*.jpeg' -print0 | xargs -0 -I{} sh -c 'printf "%s %s\n" "$(stat -c%s "{}")" "{}"' | awk '{size=$1; $1=""; print size, substr($0,2)}' | sort -n | sed -n '1,200p' || true
echo

# 5) Resumen final
echo "==> Resumen guardado en $OUT_DIR"
echo "Si quieres que intente un preprocesado rápido (resize + auto-level + threshold) y reejecute Tesseract sobre los crops, responde 'preprocesar y reejecutar' y te doy el bloque para eso."
