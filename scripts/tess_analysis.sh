#!/usr/bin/env bash
set -euo pipefail

# Ajustes
PACKAGE="com.albacontrol"
BRANCH=$(git rev-parse --abbrev-ref HEAD)
TS=$(date +%Y%m%d_%H%M%S)
OUT_DIR="artifacts_device/tess_analysis_${TS}"
PATCH_DIR="artifacts_device/patches"
TEMPLATES_DIR="artifacts_device/templates_debug/templates_debug"
CROPS_DIR="$TEMPLATES_DIR/crops"
mkdir -p "$OUT_DIR" "$PATCH_DIR"

echo "Branch: $BRANCH"
echo "Templates dir: $TEMPLATES_DIR"
echo "Crops dir: $CROPS_DIR"
echo "Output: $OUT_DIR"

# Requisitos: tesseract y jq
if ! command -v tesseract >/dev/null 2>&1; then
  echo "ERROR: tesseract no está instalado o no está en PATH. Instálalo y vuelve a ejecutar."; exit 1
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "ERROR: jq no está instalado. Instálalo y vuelve a ejecutar."; exit 1
fi

# 1) Listar archivos relevantes
echo "==> Listado de debug_*.json y crops (primeras 200 líneas)"
find "$TEMPLATES_DIR" -maxdepth 1 -type f -name 'debug_*.json' -printf "%p %s bytes\n" | sed -n '1,200p' || true
find "$CROPS_DIR" -type f -iname '*.png' -o -iname '*.jpg' -o -iname '*.jpeg' | sed -n '1,200p' || true

# 2) Preparar variantes de Tesseract a probar
# Si existen archivos *_psm*_wl_*.txt en crops, los usaremos para whitelist/psm combos.
VARIANTS_FILE="$OUT_DIR/variants_list.txt"
> "$VARIANTS_FILE"
# default variants: psm 6 and 3, no whitelist
echo "psm=6;wl=" >> "$VARIANTS_FILE"
echo "psm=3;wl=" >> "$VARIANTS_FILE"

# detect whitelist/psm files and add variants
if [ -d "$CROPS_DIR" ]; then
  for f in "$CROPS_DIR"/*_psm*_wl_*.txt "$CROPS_DIR"/*_psm*_wl_*.TXT; do
    [ -f "$f" ] || continue
    base=$(basename "$f")
    # extract psm and wl from filename if possible
    psm=$(echo "$base" | sed -n 's/.*_psm\([0-9]\+\).*/\1/p' || true)
    wl=$(cat "$f" | tr -d '\n' | tr -d '\r' | sed 's/[^A-Za-z0-9]//g' || true)
    if [ -z "$psm" ]; then psm=6; fi
    echo "psm=${psm};wl=${wl}" >> "$VARIANTS_FILE"
  done
fi

echo "Variants to run:"
cat "$VARIANTS_FILE"

# 3) Run Tesseract on each crop for each variant and collect outputs
OCR_OUT_DIR="$OUT_DIR/ocr_outputs"
mkdir -p "$OCR_OUT_DIR"
echo "==> Running Tesseract variants on crops (this may take a while)..."
while IFS= read -r var; do
  [ -z "$var" ] && continue
  psm=$(echo "$var" | cut -d';' -f1 | sed 's/psm=//')
  wl=$(echo "$var" | cut -d';' -f2 | sed 's/wl=//')
  tag="psm${psm}"
  if [ -n "$wl" ]; then tag="${tag}_wl_${wl}"; fi
  mkdir -p "$OCR_OUT_DIR/$tag"
  shopt -s nullglob
  for img in "$CROPS_DIR"/*.{png,jpg,jpeg}; do
    [ -f "$img" ] || continue
    name=$(basename "$img")
    outbase="$OCR_OUT_DIR/$tag/${name%.*}"
    outtxt="${outbase}.txt"
    # build tesseract options
    opts="--psm $psm"
    if [ -n "$wl" ]; then
      # create tessdata config file for tessedit_char_whitelist
      cfg="${outbase}_cfg.txt"
      echo "tessedit_char_whitelist $wl" > "$cfg"
      # try using configfile (older tesseract uses config filename without =)
      tesseract "$img" "$outbase" $opts configfile "$cfg" 2>/dev/null || true
      # fallback: use -c option if configfile not supported
      if [ ! -f "$outtxt" ]; then
        tesseract "$img" "$outbase" $opts -c tessedit_char_whitelist="$wl" 2>/dev/null || true
      fi
    else
      tesseract "$img" "$outbase" $opts 2>/dev/null || true
    fi
  done
  shopt -u nullglob
done < "$VARIANTS_FILE"

# 4) Map OCR outputs back to debug_*.json and compute CER per field
SUMMARY_OUT="$OUT_DIR/tess_cer_summary.txt"
echo "==> Computing CER per debug file and variant -> $SUMMARY_OUT"
# Use unquoted heredoc so shell variables like $TEMPLATES_DIR and $OCR_OUT_DIR expand
python3 - <<PY > "$SUMMARY_OUT" 2>&1
import glob,json,os,re,collections
from pathlib import Path

templates_dir = Path("$TEMPLATES_DIR")
ocr_out_dir = Path("$OCR_OUT_DIR")
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
variants = sorted([p.name for p in ocr_out_dir.iterdir() if p.is_dir()])
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
    # attempt to find crop files by prefix matching debug filename (heuristic)
    prefix = df.stem
    print("File:", df.name)
    for var in variants:
        # aggregate OCR text from all crop outputs for this variant
        texts=[]
        for tfile in sorted((ocr_out_dir/var).glob(prefix+"*")):
            try:
                txt=open(tfile,"r",encoding="utf-8").read().strip()
            except:
                txt=""
            if txt:
                texts.append(txt)
        agg = " ".join(texts)
        # compute CER for fields using aggregated OCR as fallback
        fields=[("Proveedor", form.get("etProveedor","")),
                ("NIF", form.get("etNif","")),
                ("Número", form.get("etNumero","")),
                ("Fecha", form.get("etFecha",""))]
        print("  Variant:", var, "aggregated_chars:", len(agg))
        for label, truth in fields:
            truth_norm = normalize(truth)
            pred_norm = normalize(agg)
            ld = levenshtein(pred_norm, truth_norm)
            cer = ld / max(1, len(truth_norm)) if truth_norm else (len(pred_norm) if pred_norm else 0)
            # include agg explicitly to avoid KeyError in .format
            print("    {label}: CER={cer:.3f} truth='{truth}' pred_sample='{pred}'".format(label=label,cer=cer,truth=truth,pred=agg[:40]))
    print()
PY

# 5) Create patch inside repo (respecting sanitized branch name)
SAFE_BRANCH=$(echo "$BRANCH" | sed 's#[/ ]#_#g')
PATCH_OUT="${PATCH_DIR}/${SAFE_BRANCH}_changes_${TS}.patch"
git fetch origin main 2>/dev/null || true
BASE_REF="origin/main"
if ! git rev-parse --verify "$BASE_REF" >/dev/null 2>&1; then BASE_REF="main"; fi
git format-patch "$BASE_REF" --stdout > "$PATCH_OUT" || true
echo "Patch created: $PATCH_OUT"
ls -lh "$PATCH_OUT" || true

# 6) Package results
REPORT_TAR="artifacts_device/report_tess_${SAFE_BRANCH}_${TS}.tgz"
tar -czf "$REPORT_TAR" "$OUT_DIR" "$PATCH_OUT" "$TEMPLATES_DIR" || true
echo "Report tar created: $REPORT_TAR"
ls -lh "$REPORT_TAR"

echo
echo "==> Hecho. Rutas importantes:"
echo "  OCR outputs: $OCR_OUT_DIR"
echo "  CER summary: $SUMMARY_OUT"
echo "  Patch: $PATCH_OUT"
echo "  Report tar: $REPORT_TAR"

: <<'PUSH'
# REMOTE_URL="git@github.com:usuario/repo.git"
# git remote add origin "$REMOTE_URL"
# git push -u origin "$BRANCH"
# if command -v gh >/dev/null 2>&1; then gh pr create --fill --base main || true; fi
PUSH
