#!/usr/bin/env bash
set -euo pipefail

# Ajustes (ajusta si tu estructura difiere)
TEMPLATES_DIR="artifacts_device/templates_debug/templates_debug"
CROPS_DIR="$TEMPLATES_DIR/crops"
TS=$(date +%Y%m%d_%H%M%S)
OUT_DIR="artifacts_device/tess_preproc_run_${TS}"
PRE_DIR="$OUT_DIR/preprocessed"
VAR_OUT="$OUT_DIR/variants"
SUMMARY="$OUT_DIR/cer_by_variant.txt"
mkdir -p "$PRE_DIR" "$VAR_OUT"

# Requisitos
for cmd in tesseract convert identify; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "ERROR: '$cmd' no encontrado en PATH. Instálalo y vuelve a ejecutar."; exit 1
  fi
done

echo "Templates dir: $TEMPLATES_DIR"
echo "Crops dir: $CROPS_DIR"
echo "Output: $OUT_DIR"
echo

# 1) Preparar lista de crops
CROPS=( "$CROPS_DIR"/*.{png,jpg,jpeg} )
if [ ! -e "${CROPS[0]}" ]; then
  echo "No se encontraron crops en $CROPS_DIR"; exit 1
fi

# 2) Preprocesado rápido por crop (resize 2x, auto-level, median, adaptive threshold)
echo "==> Preprocesando crops -> $PRE_DIR"
for img in "$CROPS_DIR"/*.{png,jpg,jpeg}; do
  [ -f "$img" ] || continue
  base=$(basename "$img")
  out="$PRE_DIR/${base%.*}_pre.png"
  # convert pipeline: resize 2x, auto-level, median filter, adaptive threshold
  convert "$img" -resize 200% -auto-level -median 1 -colorspace Gray -adaptive-threshold 15x15+10% -normalize "$out" || \
    convert "$img" -resize 200% -auto-level -median 1 -colorspace Gray -threshold 50% "$out" || true
  echo "Preprocessed: $out"
done

# 3) Variantes a probar (incluye whitelists por tipo)
VARIANTS=(
  "psm=6;wl="                # default block
  "psm=3;wl="                # single block
  "psm=11;wl="               # sparse text
  "psm=6;wl=0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"  # alnum (NIF)
  "psm=6;wl=0123456789-/"    # numbers/dates
)
echo "Variants:"
printf '%s\n' "${VARIANTS[@]}"

# 4) Ejecutar Tesseract por variante sobre preprocesados
echo "==> Ejecutando Tesseract sobre preprocesados"
for var in "${VARIANTS[@]}"; do
  psm=$(echo "$var" | cut -d';' -f1 | sed 's/psm=//')
  wl=$(echo "$var" | cut -d';' -f2 | sed 's/wl=//')
  tag="psm${psm}"
  if [ -n "$wl" ]; then tag="${tag}_wl"; fi
  mkdir -p "$VAR_OUT/$tag"
  for img in "$PRE_DIR"/*.{png,jpg,jpeg}; do
    [ -f "$img" ] || continue
    name=$(basename "$img")
    outbase="$VAR_OUT/$tag/${name%.*}"
    if [ -n "$wl" ]; then
      tesseract "$img" "$outbase" --psm "$psm" -c tessedit_char_whitelist="$wl" 2>/dev/null || true
    else
      tesseract "$img" "$outbase" --psm "$psm" 2>/dev/null || true
    fi
  done
done

# 5) Calcular CER por debug_*.json agregando OCR de crops por prefijo
echo "==> Calculando CER por debug file y variante -> $SUMMARY"
python3 - <<PY > "$SUMMARY" 2>&1
import glob,json,re,collections,os
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
        agg_texts=[]
        for tfile in sorted((var_out/var).glob(prefix+"*")):
            txt_path = Path(str(tfile)+'.txt') if not tfile.suffix=='.txt' else tfile
            if txt_path.exists():
                try:
                    txt = txt_path.read_text(encoding='utf-8').strip()
                except:
                    txt = ""
            else:
                try:
                    txt = open(str(tfile)+'.txt','r',encoding='utf-8').read().strip()
                except:
                    txt = ""
            if txt:
                agg_texts.append(txt)
        agg = " ".join(agg_texts)
        print("  Variant:", var, "aggregated_chars:", len(agg))
        fields=[("Proveedor", form.get("etProveedor","")),
                ("NIF", form.get("etNif","")),
                ("Número", form.get("etNumero","")),
                ("Fecha", form.get("etFecha",""))]
        for label, truth in fields:
            truth_norm = normalize(truth)
            pred_norm = normalize(agg)
            ld = levenshtein(pred_norm, truth_norm)
            cer = ld / max(1, len(truth_norm)) if truth_norm else (len(pred_norm) if pred_norm else 0)
            print("    {label}: CER={cer:.3f} truth='{truth}' pred_sample='{agg[:60]}'".format(label=label,cer=cer,truth=truth))
    print()
PY

# 6) Empaquetar resultados
REPORT_TAR="${OUT_DIR}.tgz"
tar -czf "$REPORT_TAR" -C "$(dirname "$OUT_DIR")" "$(basename "$OUT_DIR")" || true
echo "Report tar creado: $REPORT_TAR"
ls -lh "$REPORT_TAR"

echo
echo "Hecho. Rutas importantes:"
echo "  Preprocessed crops: $PRE_DIR"
echo "  Tesseract outputs: $VAR_OUT"
echo "  CER summary: $SUMMARY"
echo "  Paquete: $REPORT_TAR"

echo
echo "Qué pegar aquí después de ejecutar:"
echo " - Línea 'debug files found: N' y 'variants found: [...]' del inicio del resumen"
echo " - Primeras 12 líneas del archivo CER ($SUMMARY)"
echo " - Ruta del tar creado y su 'ls -lh' línea"
