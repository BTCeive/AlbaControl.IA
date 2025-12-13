#!/usr/bin/env bash
set -euo pipefail

ASSETS_IMG_DIR="android/app/src/main/assets/test_images"
SAMPLE_IMG="$ASSETS_IMG_DIR/sample.jpg"
SCRIPT="scripts/run_ocr_test.sh"

echo "==> Asegurando directorio de imágenes de prueba: $ASSETS_IMG_DIR"
mkdir -p "$ASSETS_IMG_DIR"

if [ ! -f "$SAMPLE_IMG" ]; then
  echo "==> Creando sample.jpg de prueba (pequeña imagen blanca)"
  # Imagen JPEG 1x1 blanca en base64
  cat > "$SAMPLE_IMG" <<'B64'
/9j/4AAQSkZJRgABAQAAAQABAAD/2wCEAAIBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ
EBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQH/wAALCAABAAIBASIA/8QAFQABAQAAAAAA
AAAAAAAAAAAAf/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIQAxAAAAH/AP/EABQQAQAA
AAAAAAAAAAAAAAAAAAH/2gAIAQEAAQUC/8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAgBAwEBPw
D/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAECAQE/AFP/xAAUEAEAAAAAAAAAAAAAAAAAAAAA
/9oACAEBAAY/Al//2Q==
B64
  echo "Creado: $SAMPLE_IMG"
else
  echo "Ya existe: $SAMPLE_IMG (no se sobrescribe)"
fi

echo
echo "==> Verificando script de prueba: $SCRIPT"
if [ ! -x "$SCRIPT" ]; then
  echo "Haciendo ejecutable: $SCRIPT"
  chmod +x "$SCRIPT" || true
fi

echo
echo "==> Ejecutando script de prueba (compilar, instalar, lanzar TestOcrActivity y extraer ocr_result.json)"
echo "Nota: la build puede requerir Android SDK/NDK y tardar varios minutos."
"$SCRIPT"

echo
echo "==> Resultado: revisa artifacts_device/ocr_test/ocr_result.json si se extrajo correctamente."
#!/usr/bin/env bash
set -euo pipefail

# Ajustes (modifica si hace falta)
PAIRS_DIR="artifacts_device/pairing_timestamp/pairs"
OUT_BASE="artifacts_device/learning_run_$(date +%Y%m%d_%H%M%S)"
OCR_OUT="$OUT_BASE/ocr_outputs"
CER_OUT="$OUT_BASE/cer_summary.txt"
LEARN_QUEUE="$OUT_BASE/learning_queue"
mkdir -p "$OCR_OUT" "$LEARN_QUEUE"

echo "Pairs dir: $PAIRS_DIR"
echo "Output base: $OUT_BASE"
echo

# 1) Preparar lista de crops emparejados: intentamos localizar preproc_*.png con el mismo timestamp que el .txt emparejado
echo "==> Localizando crops emparejados y preparando lista"
pairs=( "$PAIRS_DIR"/debug_*.json )
if [ ${#pairs[@]} -eq 0 ]; then
  echo "No se encontraron pares en $PAIRS_DIR"; exit 1
fi

# heurística: para cada debug file, buscar preproc_<ts>.* en templates_debug/crops o en variants/psm6
CROPS_ROOT="artifacts_device/templates_debug/templates_debug/crops"
PSM6_DIR=$(ls -td artifacts_device/tess_debug_run_* 2>/dev/null | head -n1 || true)
PSM6_DIR="${PSM6_DIR:-}/variants/psm6"

declare -A debug_to_crop
for df in "${pairs[@]}"; do
  base=$(basename "$df" .json)
  # try exact match by name in crops
  crop=$(ls -1 "${CROPS_ROOT}/${base}"* 2>/dev/null | head -n1 || true)
  if [ -z "$crop" ]; then
    # try matching by timestamp number inside filename
    ts=$(echo "$base" | grep -oE '[0-9]{8,}' | head -n1 || true)
    if [ -n "$ts" ]; then
      crop=$(ls -1 "${CROPS_ROOT}"/*"${ts}"* 2>/dev/null | head -n1 || true)
    fi
  fi
  if [ -z "$crop" ]; then
    # fallback: try psm6 outputs (preproc_*.png)
    crop=$(ls -1 "${CROPS_ROOT}"/preproc_* 2>/dev/null | head -n1 || true)
  fi
  if [ -n "$crop" ]; then
    debug_to_crop["$df"]="$crop"
    echo "Mapped: $(basename "$df") -> $(basename "$crop")"
  else
    echo "No crop found for: $(basename "$df")"
  fi
done

# 2) Re-ejecutar Tesseract por par con variantes orientadas a campos
VARIANTS=(
  "oem=1;psm=6;wl="
  "oem=1;psm=3;wl="
  "oem=1;psm=11;wl="
  "oem=1;psm=6;wl=0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
  "oem=1;psm=6;wl=0123456789-/"
)

echo
echo "==> Ejecutando Tesseract (OEM=1) por par y variante"
for df in "${!debug_to_crop[@]}"; do
  crop="${debug_to_crop[$df]}"
  debugname=$(basename "$df" .json)
  for var in "${VARIANTS[@]}"; do
    oem=$(echo "$var" | sed -n 's/.*oem=\([0-9]\+\).*/\1/p' || echo "1")
    psm=$(echo "$var" | sed -n 's/.*psm=\([0-9]\+\).*/\1/p' || echo "6")
    wl=$(echo "$var" | sed -n 's/.*wl=\(.*\)/\1/p' || echo "")
    tag="oem${oem}_psm${psm}"
    [ -n "$wl" ] && tag="${tag}_wl"
    outdir="$OCR_OUT/$tag"
    mkdir -p "$outdir"
    outbase="$outdir/${debugname}"
    if [ -n "$wl" ]; then
      tesseract "$crop" "$outbase" --oem "$oem" --psm "$psm" -c tessedit_char_whitelist="$wl" 2>/dev/null || true
    else
      tesseract "$crop" "$outbase" --oem "$oem" --psm "$psm" 2>/dev/null || true
    fi
  done
done

# 3) Generar learning_queue entries
echo
echo "==> Generando learning_queue (ground-truth + OCR outputs)"
for df in "${!debug_to_crop[@]}"; do
  debugname=$(basename "$df")
  jfile="$LEARN_QUEUE/${debugname}.json"
  python3 - <<PY > "$jfile"
import json
from pathlib import Path

df = Path("$df")
try:
    j = json.load(open(df,'r',encoding='utf-8'))
except:
    j = {}
ocr = {}
ocr_root = Path("$OCR_OUT")
for var_dir in sorted(ocr_root.iterdir()):
    if not var_dir.is_dir(): continue
    txtf = var_dir / (df.stem + '.txt')
    if txtf.exists():
        try:
            ocr[var_dir.name] = txtf.read_text(encoding='utf-8').strip()
        except:
            ocr[var_dir.name] = ""
    else:
        ocr[var_dir.name] = ""
out = {"debug_file": df.name, "form": j.get("form",{}), "ocr_variants": ocr}
json.dump(out, open("$jfile",'w',encoding='utf-8'), ensure_ascii=False, indent=2)
print("Wrote", "$jfile")
PY
done

# 4) Calcular CER por variante
echo
echo "==> Calculando CER por variante -> $CER_OUT"
python3 - <<PY > "$CER_OUT" 2>&1
import json,re
from pathlib import Path

def normalize(s):
    if s is None: return ''
    return re.sub(r'[^A-Za-z0-9]','',str(s).strip().upper())

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

pairs = sorted(Path("$LEARN_QUEUE").glob("debug_*.json"))
variants = []
if pairs:
    sample = json.load(open(pairs[0],'r',encoding='utf-8'))
    variants = sorted(sample.get("ocr_variants",{}).keys())

print("paired debug files:", len(pairs))
print("variants:", variants)
print()
for p in pairs:
    data = json.load(open(p,'r',encoding='utf-8'))
    form = data.get("form",{})
    print("File:", p.name)
    for var in variants:
        pred = data.get("ocr_variants",{}).get(var,"") or ""
        print("  Variant:", var, "aggregated_chars:", len(pred))
        fields=[("Proveedor", form.get("etProveedor","")),
                ("NIF", form.get("etNif","")),
                ("Número", form.get("etNumero","")),
                ("Fecha", form.get("etFecha",""))]
        for label, truth in fields:
            tnorm = normalize(truth)
            pnorm = normalize(pred)
            ld = levenshtein(pnorm, tnorm)
            cer = ld / max(1, len(tnorm)) if tnorm else (len(pnorm) if pnorm else 0)
            print("    {label}: CER={cer:.3f} truth='{truth}' pred_sample='{pred[:60]}'".format(label=label,cer=cer,truth=truth))
    print()
PY

# 5) Empaquetar resultados
REPORT_TAR="$OUT_BASE/report_learning_$(date +%Y%m%d_%H%M%S).tgz"
tar -czf "$REPORT_TAR" -C "$(dirname "$OUT_BASE")" "$(basename "$OUT_BASE")" || true

echo
echo "==> Hecho. Rutas importantes:"
echo "  OCR outputs: $OCR_OUT"
echo "  Learning queue (one JSON per debug): $LEARN_QUEUE"
echo "  CER summary: $CER_OUT"
echo "  Paquete: $REPORT_TAR"
echo
