#!/usr/bin/env bash
set -euo pipefail

TEMPLATES_DIR="artifacts_device/templates_debug/templates_debug"
PSM_DIR=$(ls -td artifacts_device/tess_debug_run_* 2>/dev/null | head -n1)/variants/psm6
OUT_DIR="artifacts_device/pairing_timestamp"
mkdir -p "$OUT_DIR/pairs" "$OUT_DIR/logs"

echo "Templates dir: $TEMPLATES_DIR"
echo "PSM txt dir: $PSM_DIR"
echo "Output dir: $OUT_DIR"
echo

# 1) Collect debug files and extract numeric timestamp tokens (>=10 digits)
declare -A debug_ts_map
for df in "$TEMPLATES_DIR"/debug_*.json; do
  [ -f "$df" ] || continue
  name=$(basename "$df")
  # extract first long numeric sequence (10+ digits) from filename
  ts=$(echo "$name" | grep -oE '[0-9]{10,}' | head -n1 || true)
  if [ -z "$ts" ]; then
    # fallback: use file mtime as timestamp
    ts=$(stat -c %Y "$df")
  fi
  debug_ts_map["$df"]="$ts"
done

# 2) Collect preproc txt files and extract numeric timestamp tokens
declare -A txt_ts_map
for t in "$PSM_DIR"/preproc_*.txt; do
  [ -f "$t" ] || continue
  tname=$(basename "$t")
  ts=$(echo "$tname" | grep -oE '[0-9]{10,}' | head -n1 || true)
  if [ -z "$ts" ]; then
    ts=$(stat -c %Y "$t")
  fi
  txt_ts_map["$t"]="$ts"
done

echo "Found debug files: ${#debug_ts_map[@]}"
echo "Found preproc txt files: ${#txt_ts_map[@]}"
echo

# 3) For each debug file, find closest txt by absolute timestamp difference
paired_count=0
unmatched_list="$OUT_DIR/unmatched_debug.txt"
: > "$unmatched_list"
for df in "${!debug_ts_map[@]}"; do
  dts=${debug_ts_map["$df"]}
  best=""
  bestdiff=0
  for t in "${!txt_ts_map[@]}"; do
    tts=${txt_ts_map["$t"]}
    diff=$(( dts > tts ? dts - tts : tts - dts ))
    if [ -z "$best" ] || [ "$diff" -lt "$bestdiff" ]; then
      best="$t"
      bestdiff="$diff"
    fi
  done
  if [ -n "$best" ]; then
    # copy pair
    base_debug=$(basename "$df")
    base_txt=$(basename "$best")
    cp -v "$df" "$OUT_DIR/pairs/${base_debug}" 2>/dev/null || true
    cp -v "$best" "$OUT_DIR/pairs/${base_debug%.json}.txt" 2>/dev/null || true
    paired_count=$((paired_count+1))
    echo "Paired: $base_debug  <--->  $base_txt  (diff=${bestdiff}s)" >> "$OUT_DIR/logs/pairing.log"
    # remove matched txt from pool to avoid reuse
    unset "txt_ts_map[$best]"
  else
    echo "$df" >> "$unmatched_list"
  fi
done

echo "Paired count: $paired_count"
echo "Unmatched debug files listed in: $unmatched_list"
echo

# 4) Compute CER per paired file (Proveedor, NIF, Número, Fecha)
CER_OUT="$OUT_DIR/cer_by_pairing.txt"
python3 - <<PY > "$CER_OUT" 2>&1
import json,re,os
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

pairs_dir = Path("$OUT_DIR/pairs")
pairs = sorted(pairs_dir.glob("debug_*.json"))
print("paired debug files:", len(pairs))
print()
for df in pairs:
    try:
        j=json.load(open(df,'r',encoding='utf-8'))
    except Exception as e:
        print("error loading", df.name, e); continue
    form=j.get("form",{})
    txt_path = df.with_suffix('.txt')
    pred_text = ""
    if txt_path.exists():
        try:
            pred_text = txt_path.read_text(encoding='utf-8').strip()
        except:
            pred_text = ""
    print("File:", df.name)
    fields=[("Proveedor", form.get("etProveedor","")),
            ("NIF", form.get("etNif","")),
            ("Número", form.get("etNumero","")),
            ("Fecha", form.get("etFecha",""))]
    for label, truth in fields:
        tnorm = normalize(truth)
        pnorm = normalize(pred_text)
        ld = levenshtein(pnorm, tnorm)
        cer = ld / max(1, len(tnorm)) if tnorm else (len(pnorm) if pnorm else 0)
        print("  {label}: CER={cer:.3f} truth='{truth}' pred_sample='{pred_text[:60]}'".format(label=label,cer=cer,truth=truth))
    print()
PY

echo "CER summary saved to: $CER_OUT"
sed -n '1,200p' "$CER_OUT" || true

echo
echo "Paired files copied to: $OUT_DIR/pairs"
echo "Pairing log: $OUT_DIR/logs/pairing.log"
echo "If pairing looks wrong, you can re-run with a different matching rule (e.g., nearest by filename substring or by ordering)."
