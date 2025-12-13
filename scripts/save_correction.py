#!/usr/bin/env python3
import json,sys
from pathlib import Path
from datetime import datetime

OUT_DIR = Path("artifacts_device/persisted_corrections")
OUT_DIR.mkdir(parents=True, exist_ok=True)

def save_correction(payload):
    ts = datetime.utcnow().strftime("%Y%m%dT%H%M%SZ")
    debug = payload.get("debug_id","debug_unknown")
    fname = OUT_DIR / f"{debug}_corr_{ts}.json"
    fname.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print("WROTE", fname)

if __name__ == "__main__":
    # usage: cat payload.json | python3 scripts/save_correction.py
    data = json.load(sys.stdin)
    save_correction(data)
