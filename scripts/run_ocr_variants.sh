#!/usr/bin/env bash
# Run several Tesseract parameter variants over an input image or a directory of crops.
# Usage: ./run_ocr_variants.sh <image|crops_dir> <outdir>

set -euo pipefail
if [ "$#" -lt 2 ]; then
  echo "Usage: $0 <image|crops_dir> <outdir>"
  echo "If first arg is a directory, script will iterate images inside it."
  exit 2
fi

SRC="$1"
OUTDIR="$2"
mkdir -p "$OUTDIR"

PSMS=(3 6 7 11)
# 3 = Fully automatic page segmentation, 6 = Assume a single uniform block, 7 = single text line, 11 = sparse text
WHITELISTS=("" "0123456789" "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ" "ABCDEFGHIJKLMNOPQRSTUVWXYZ")

run_single() {
  local img="$1"
  local base=$(basename "$img")
  for psm in "${PSMS[@]}"; do
    for wl in "${WHITELISTS[@]}"; do
      suffix="${base}_psm${psm}"
      if [ -n "$wl" ]; then
        wlname=$(echo "$wl" | sed 's/[^A-Za-z0-9]/_/g')
        suffix+="_wl_${wlname}"
      else
        suffix+="_wl_all"
      fi
      outtxt="$OUTDIR/${suffix}.txt"
      echo "Running $img psm=$psm whitelist='${wl}' -> $outtxt"
      if command -v tesseract >/dev/null 2>&1; then
        if [ -n "$wl" ]; then
          tesseract "$img" "$OUTDIR/tmp_${suffix}" -l spa --psm $psm -c tessedit_char_whitelist="$wl" >/dev/null 2>&1 || true
          mv "$OUTDIR/tmp_${suffix}.txt" "$outtxt" || true
        else
          tesseract "$img" "$OUTDIR/tmp_${suffix}" -l spa --psm $psm >/dev/null 2>&1 || true
          mv "$OUTDIR/tmp_${suffix}.txt" "$outtxt" || true
        fi
      else
        echo "tesseract CLI not found; skipping actual run" > "$outtxt"
      fi
    done
  done
}

# If SRC is a directory, iterate images inside, otherwise process single image
if [ -d "$SRC" ]; then
  shopt -s nullglob
  for img in "$SRC"/*.{png,jpg,jpeg}; do
    [ -f "$img" ] || continue
    run_single "$img"
  done
else
  run_single "$SRC"
fi

echo "Variants written to $OUTDIR"
