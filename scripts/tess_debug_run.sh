#!/usr/bin/env bash
set -euo pipefail

# Ajustes
TEMPLATES_DIR="artifacts_device/templates_debug/templates_debug"
CROPS_DIR="$TEMPLATES_DIR/crops"
OCR_OUT_DIR_BASE="artifacts_device/tess_debug_run_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$OCR_OUT_DIR_BASE"
SUMMARY="$OCR_OUT_DIR_BASE/summary.txt"

echo "Templates dir: $TEMPLATES_DIR"
echo "Crops dir: $CROPS_DIR"
echo "Output base: $OCR_OUT_DIR_BASE"
echo

# 1) Listar ocr_outputs y crops
echo "=== Listado ocr_outputs (si existe) ==="
ls -lah "$OCR_OUT_DIR_BASE" 2>/dev/null || true
echo
echo "=== Listado crops (primeras 200 líneas) ==="
find "$CROPS_DIR" -maxdepth 1 -type f -iname '*.png' -o -iname '*.jpg' -o -iname '*.jpeg' -printf "%p %s bytes\n" | sed -n '1,200p' || true
echo

# 2) Mostrar un ejemplo de .txt generado previamente (si existe)
EXAMPLE_TX=$(find "$TEMPLATES_DIR" -type f -name '*.txt' | head -n1 || true)
if [ -n "$EXAMPLE_TX" ]; then
  echo "=== Ejemplo de .txt encontrado: $EXAMPLE_TX (primeras 200 líneas) ==="
  sed -n '1,200p' "$EXAMPLE_TX" || true
else
  echo "No se encontró .txt previo en $TEMPLATES_DIR"
fi
echo

# 3) Mostrar info de un crop de ejemplo y generar miniatura base64 (opcional)
EXAMPLE_IMG=$(find "$CROPS_DIR" -type f -iname '*.png' -o -iname '*.jpg' -o -iname '*.jpeg' | head -n1 || true)
if [ -n "$EXAMPLE_IMG" ]; then
  echo "=== Ejemplo de crop: $EXAMPLE_IMG ==="
  ls -lh "$EXAMPLE_IMG"
  # show image dimensions if identify available
  if command -v identify >/dev/null 2>&1; then
    identify -format "Dimensions: %wx%h, Format: %m\n" "$EXAMPLE_IMG" || true
  fi
  # create small base64 preview file for manual inspection (not printed here)
  PREVIEW="$OCR_OUT_DIR_BASE/preview_$(basename "$EXAMPLE_IMG").b64"
  base64 "$EXAMPLE_IMG" > "$PREVIEW" || true
  echo "Miniatura base64 guardada en: $PREVIEW"
else
  echo "No se encontró ningún crop en $CROPS_DIR"
fi
echo

# 4) Ejecutar variantes Tesseract por campo (Proveedor, NIF, Número, Fecha)
#    - Proveedor: alfanum + espacios (no whitelist)
#    - NIF: mayúsculas y dígitos (whitelist A-Z0-9)
#    - Número: dígitos y guiones
#    - Fecha: dígitos y /-.
VAR_OUT="$OCR_OUT_DIR_BASE/variants"
mkdir -p "$VAR_OUT"

# variants: psm and optional whitelist
VARIANTS=(
  "psm=6;wl="        # default
  "psm=3;wl="        # single block
  "psm=6;wl=0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"  # alnum for NIF
  "psm=6;wl=0123456789-/"  # numbers/dates
  "psm=11;wl="       # sparse text
)

echo "Variants to run:"
printf '%s\n' "${VARIANTS[@]}"

enable_nullglob=false
shopt -s nullglob || enable_nullglob=true

# For each crop, run each variant and save outputs under variant/<cropname>.txt
for var in "${VARIANTS[@]}"; do
  psm=$(echo "$var" | cut -d';' -f1 | sed 's/psm=//')
  wl=$(echo "$var" | cut -d';' -f2 | sed 's/wl=//')
  tag="psm${psm}"
  if [ -n "$wl" ]; then tag="${tag}_wl"; fi
  mkdir -p "$VAR_OUT/$tag"
  for img in "$CROPS_DIR"/*.{png,jpg,jpeg}; do
    [ -f "$img" ] || continue
    name=$(basename "$img")
    outbase="$VAR_OUT/$tag/${name%.*}"
    # run tesseract; prefer -c tessedit_char_whitelist if wl present
    if [ -n "$wl" ]; then
      tesseract "$img" "$outbase" --psm "$psm" -c tessedit_char_whitelist="$wl" 2>/dev/null || true
    else
      tesseract "$img" "$outbase" --psm "$psm" 2>/dev/null || true
    fi
  done
done

if [ "$enable_nullglob" = true ]; then
  shopt -u nullglob || true
fi

echo "Tesseract runs finished. Outputs in: $VAR_OUT"
echo

# 5) Agregar paso de OCR por campo: intentar mapear crops a debug_*.json por prefijo y agregar textos
#    Luego calcular CER por campo usando aggregated OCR per debug file
ANALYSIS="$OCR_OUT_DIR_BASE/cer_by_variant.txt"
python3 - <<PY > "$ANALYSIS" 2>&1
import glob,json,os,re,collections
from pathlib import Path

templates_dir = Path("$TEMPLATES_DIR")
var_out = Path("$VAR_OUT")
def normalize(s):
    if s is None: return ""
    return re.sub(r'[^A-Za-z0-9]', '', str(s).strip().upper())

def levenshtein(a,b):
    if a==b: return 0
    if len(a)==0: return len(b)
    if len(b)==0: return len(a)
    prev=list(range(len(b)+1))
    for i,ca in enumerate(a,1):
        cur=[i]+[0]*len(b)
        for j,cb in enumerate(b,1):
            add = 0 if ca==cb else 1
            cur[j]=min(prev[j]+1, cur[j-1]+1, prev[j-1]+add)
        prev=cur
    return prev[-1]

debug_files = sorted(templates_dir.glob("debug_*.json"))
variants = sorted([p.name for p in var_out.iterdir() if p.is_dir()]) if var_out.exists() else []
print("debug files found:", len(debug_files))
print("variants found:", variants)
print()

for df in debug_files:
    try:
        j=json.load(open(df,'r',encoding='utf-8'))
    except Exception as e:
        print("error loading", df.name, e)
        continue
    form=j.get("form",{})
    prefix = df.stem
    print("File:", df.name)
    for var in variants:
        # aggregate OCR text from all crop outputs for this variant that start with prefix
        agg_texts=[]
        for tfile in sorted((var_out/var).glob(prefix+"*")):
            try:
                txt_path = tfile.with_suffix('.txt')
                txt = txt_path.read_text(encoding='utf-8').strip()
            except Exception:
                try:
                    txt = (str(tfile)+'.txt')
                    txt = open(txt,'r',encoding='utf-8').read().strip()
                except Exception:
                    txt = ""
            if txt:
                agg_texts.append(txt)
        agg = " ".join(agg_texts)
        print("  Variant:", var, "aggregated_chars:", len(agg))
        # compute CER per field using aggregated OCR as fallback
        fields=[("Proveedor", form.get("etProveedor","")),
                ("NIF", form.get("etNif","")),
                ("Número", form.get("etNumero","")),
                ("Fecha", form.get("etFecha",""))]
        for label, truth in fields:
            truth_norm = normalize(truth)
            pred_norm = normalize(agg)
            ld = levenshtein(pred_norm, truth_norm)
            cer = ld / max(1, len(truth_norm)) if truth_norm else (len(pred_norm) if pred_norm else 0)
            print("    {label}: CER={cer:.3f} truth='{truth}' pred_sample='{pred}'".format(label=label,cer=cer,truth=truth,pred=agg[:60]))
    print()
PY

echo "CER analysis saved to: $ANALYSIS"
sed -n '1,200p' "$ANALYSIS" || true
echo

# 6) Guardar resumen y empaquetar
echo "Resumen guardado en $SUMMARY"
echo "Files created under: $OCR_OUT_DIR_BASE"
tar -czf "${OCR_OUT_DIR_BASE}.tgz" -C "$(dirname "$OCR_OUT_DIR_BASE")" "$(basename "$OCR_OUT_DIR_BASE")" || true
echo "Tar creado: ${OCR_OUT_DIR_BASE}.tgz"
ls -lh "${OCR_OUT_DIR_BASE}.tgz"

echo
echo "=== FIN ==="
echo "Pega aquí:"
echo " - Línea 'debug files found: N' y 'variants found: [...]' del inicio del análisis"
echo " - Primeras 12 líneas del archivo CER (artifacts_device/tess_debug_run_*/cer_by_variant.txt)"
echo " - Ruta del tar creado y su 'ls -lh' línea"
