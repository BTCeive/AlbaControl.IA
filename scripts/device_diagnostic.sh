#!/usr/bin/env bash
set -euo pipefail

# Ajustes
PACKAGE="com.albacontrol"   # cambia si tu applicationId es distinto
DEVICE=$(adb devices -l | sed -n '2p' | awk '{print $1}' || true)
TS=$(date +%Y%m%d_%H%M%S)
OUT_DIR="artifacts_device/diagnostic_${TS}"
LOG_DIR="$OUT_DIR/logs"
PULL_DIR="$OUT_DIR/device_pull"
mkdir -p "$LOG_DIR" "$PULL_DIR"

if [ -z "$DEVICE" ]; then
  echo "ERROR: No hay dispositivo adb conectado. Conecta el dispositivo y vuelve a ejecutar."; exit 1
fi

echo "Device: $DEVICE"
echo "Output: $OUT_DIR"

# 1) Capturar logcat filtrado (30s)
LOGFILE="$LOG_DIR/logcat_filtered_${TS}.txt"
echo "Iniciando captura de logcat filtrado -> $LOGFILE (30s)"
adb -s "$DEVICE" logcat -c
# Filtrar por palabras clave relevantes; ajusta si tu app usa otros tags
adb -s "$DEVICE" logcat --format threadtime | grep -E --line-buffered "templates_debug|tables_dump|persist|saveTemplate|enqueue|training|debug_|ERROR|Exception|persistDebug|saveDebug|enqueueForTraining|learning|dedup|duplicate|already exists" > "$LOGFILE" &
LOG_PID=$!
sleep 30
kill "$LOG_PID" 2>/dev/null || true
echo "Captura logcat finalizada."

# 2) Listado de rutas candidatas en el dispositivo
LISTING="$OUT_DIR/device_listing.txt"
echo "Listando rutas candidatas en el dispositivo (sdcard, Download, app files) -> $LISTING"
adb -s "$DEVICE" shell "ls -la /sdcard /sdcard/Download /sdcard/Android/data/${PACKAGE}/files 2>/dev/null" > "$LISTING" || true
sed -n '1,200p' "$LISTING" || true

# 3) Buscar y descargar debug_*.json y tables_dump_latest.json
FOUND="$OUT_DIR/found_paths.txt"
echo "Buscando debug_*.json y tables_dump_latest.json en /sdcard (esto puede tardar)..."
adb -s "$DEVICE" shell find /sdcard -type f -name 'debug_*.json' -o -name 'tables_dump_latest.json' 2>/dev/null > "$FOUND" || true
echo "Rutas encontradas:"
sed -n '1,200p' "$FOUND" || true

# Intentos de pull en ubicaciones comunes
echo "Intentando pulls comunes..."
adb -s "$DEVICE" pull "/sdcard/Android/data/${PACKAGE}/files/templates_debug" "$PULL_DIR/templates_debug" || true
adb -s "$DEVICE" pull "/sdcard/Android/data/${PACKAGE}/files/tables_dump_latest.json" "$PULL_DIR/tables_dump_latest.json" || true

# Pull de todas las rutas encontradas
while IFS= read -r remote; do
  [ -z "$remote" ] && continue
  fname=$(basename "$remote")
  echo "Pulling $remote -> $PULL_DIR/$fname"
  adb -s "$DEVICE" pull "$remote" "$PULL_DIR/$fname" || true
done < "$FOUND"

echo "Archivos descargados:"
ls -lh "$PULL_DIR" || true

# 4) Comparación y métricas CER por campo
COMPARE_OUT="$OUT_DIR/compare_summary.txt"
python3 - <<'PY' > "$COMPARE_OUT" 2>&1
import glob, json, os, re, collections, math
from pathlib import Path

p = Path("$PULL_DIR")
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

summary = {"files": len(files), "per_file": []}
for f in files:
    try:
        j=json.load(open(f,'r',encoding='utf-8'))
    except Exception as e:
        summary["per_file"].append({"file": f.name, "error": str(e)})
        continue
    form=j.get("form",{})
    ocr=j.get("ocr",{})
    fields = [
        ("Proveedor", form.get("etProveedor",""), ocr.get("proveedor") or ocr.get("etProveedor","")),
        ("NIF", form.get("etNif",""), ocr.get("nif") or ocr.get("etNif","")),
        ("Número", form.get("etNumero",""), ocr.get("numero") or ocr.get("etNumero","")),
        ("Fecha", form.get("etFecha",""), ocr.get("fecha") or ocr.get("etFecha","")),
    ]
    metrics = {"file": f.name, "fields": []}
    for label, truth, pred in fields:
        tnorm = normalize(truth)
        pnorm = normalize(pred)
        ld = levenshtein(pnorm, tnorm)
        cer = ld / max(1, len(tnorm)) if tnorm else (len(pnorm) if pnorm else 0)
        metrics["fields"].append({"label": label, "truth": truth, "ocr": pred, "cer": round(cer,3)})
    summary["per_file"].append(metrics)

# If multiple debug files with same base name, attempt pairwise diffs by basename prefix
bybase = collections.defaultdict(list)
for f in files:
    base = f.name
    key = base.split("_",1)[0]
    bybase[key].append(f.name)

print("debug files found:", len(files))
print()
print("Per-file CER summary:")
for m in summary["per_file"]:
    print("-", m["file"])
    for fld in m["fields"]:
        print("   {label}: CER={cer}  truth='{truth}'  ocr='{ocr}'".format(**fld))
print()
print("Groups by prefix (possible same doc multiple versions):")
for k,v in bybase.items():
    if len(v)>1:
        print("  ", k, "->", v)
print()
# show top tokens across all files
cnt = collections.Counter()
for f in files:
    try:
        j=json.load(open(f,'r',encoding='utf-8'))
    except:
        continue
    ocr=j.get("ocr",{})
    blocks=ocr.get("blocks") if isinstance(ocr,dict) else []
    for b in blocks:
        t=b.get("text","") or ""
        toks=re.findall(r'[A-Za-z0-9]+', t.upper())
        for tk in toks:
            if len(tk)>1:
                cnt[tk]+=1
print("Top tokens (top 40):")
for k,c in cnt.most_common(40):
    print(k, c)
PY

echo "Comparación y métricas guardadas en: $COMPARE_OUT"
sed -n '1,400p' "$COMPARE_OUT" || true

# 5) Buscar en logs hits relevantes (save/enqueue/dedup/errors)
echo
echo "=== Hits clave en logcat filtrado ==="
grep -nE "save|persist|enqueue|training|dedup|duplicate|already exists|Exception|ERROR" "$LOGFILE" | sed -n '1,200p' || true

# 6) Empaquetar resultados
TAR="$OUT_DIR/diagnostic_${TS}.tgz"
tar -czf "$TAR" "$OUT_DIR" || true
echo "Diagnostics packaged: $TAR"
ls -lh "$TAR"

echo

echo "Hecho. Rutas importantes:"
echo "  Log filtrado: $LOGFILE"
echo "  Pulled files: $PULL_DIR"
echo "  Comparación: $COMPARE_OUT"
echo "  Paquete: $TAR"
