#!/usr/bin/env bash
set -euo pipefail

# Ajustes
BRANCH=$(git rev-parse --abbrev-ref HEAD)
# sanitize branch for filenames (replace slashes)
BRANCH_SAFE=${BRANCH//\//_}
TS=$(date +%Y%m%d_%H%M%S)
PATCH_DIR="artifacts_device/patches"
ANALYSIS_DIR="artifacts_device/analysis_${TS}"
TEMPLATES_DIR="artifacts_device/templates_debug/templates_debug"
mkdir -p "$PATCH_DIR" "$ANALYSIS_DIR"

# 1) Crear patch dentro del repo (respecto a origin/main o main)
echo "==> Creando patch para la rama $BRANCH..."
git fetch origin main 2>/dev/null || true
BASE_REF="origin/main"
if ! git rev-parse --verify "$BASE_REF" >/dev/null 2>&1; then
  BASE_REF="main"
fi
mkdir -p "$PATCH_DIR"
PATCH_OUT="${PATCH_DIR}/${BRANCH_SAFE}_changes_${TS}.patch"
git format-patch "$BASE_REF" --stdout > "$PATCH_OUT" || true
echo "Patch creado: $PATCH_OUT"
ls -lh "$PATCH_OUT" || true
echo "Primeras 200 líneas del patch:"
sed -n '1,200p' "$PATCH_OUT" || true

# 2) Analizar CER por debug_*.json en templates_debug
echo
echo "==> Analizando CER en $TEMPLATES_DIR (si existe)..."
PY_OUT="${ANALYSIS_DIR}/cer_summary.txt"
python3 - <<'PY' > "$PY_OUT" 2>&1
import glob,json,os,re,collections
from pathlib import Path

p = Path("$TEMPLATES_DIR")
files = sorted(p.glob("debug_*.json"))
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

print('debug files found:', len(files))
cnt=collections.Counter()
for f in files:
    try:
        j=json.load(open(f,'r',encoding='utf-8'))
    except Exception as e:
        print('error loading', f.name, e)
        continue
    form=j.get('form',{})
    ocr=j.get('ocr',{})
    fields=[('Proveedor', form.get('etProveedor',''), ocr.get('proveedor') or ocr.get('etProveedor','')),
            ('NIF', form.get('etNif',''), ocr.get('nif') or ocr.get('etNif','')),
            ('Número', form.get('etNumero',''), ocr.get('numero') or ocr.get('etNumero','')),
            ('Fecha', form.get('etFecha',''), ocr.get('fecha') or ocr.get('etFecha',''))]
    print('-', f.name)
    for label, truth, pred in fields:
        tnorm=normalize(truth); pnorm=normalize(pred)
        ld=levenshtein(pnorm, tnorm)
        cer = ld / max(1, len(tnorm)) if tnorm else (len(pnorm) if pnorm else 0)
        print("   {label}: CER={cer:.3f} truth='{truth}' ocr='{pred}'".format(label=label,cer=cer,truth=truth,pred=pred))
    blocks=ocr.get('blocks') if isinstance(ocr,dict) else []
    for b in blocks:
        t=b.get('text','') or ''
        toks=re.findall(r'[A-Za-z0-9]+', t.upper())
        for tk in toks:
            if len(tk)>1: cnt[tk]+=1
print()
print('Top tokens (top 40):')
for k,c in cnt.most_common(40):
    print(k, c)
PY

echo "Resumen CER guardado en: $PY_OUT"
sed -n '1,200p' "$PY_OUT" || true

# 3) Empaquetar patch y análisis
TAR_DIR="artifacts_device"
mkdir -p "$TAR_DIR"
TAR_OUT="${TAR_DIR}/report_${BRANCH_SAFE}_${TS}.tgz"
tar -czf "$TAR_OUT" "$PATCH_DIR" "$ANALYSIS_DIR" "$TEMPLATES_DIR" || true

echo
echo "==> Paquete creado: $TAR_OUT"
ls -lh "$TAR_OUT"

echo
echo "Hecho. Rutas importantes:"
echo "  Patch: $PATCH_OUT"
echo "  CER summary: $PY_OUT"
echo "  Paquete: $TAR_OUT"
