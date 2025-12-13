#!/usr/bin/env python3
import json
from pathlib import Path

IN_DIR = Path("artifacts_device/persisted_corrections")
OUT_DIR = Path("artifacts_device/learning_queue_from_corrections")
OUT_DIR.mkdir(parents=True, exist_ok=True)

def safe_get(d,k,default=""):
    return d.get(k,default)

files = sorted(IN_DIR.glob("*.json"))
count = 0
for f in files:
    try:
        j = json.load(open(f,'r',encoding='utf-8'))
    except Exception as e:
        print("skip", f, e)
        continue
    # minimal validation: must have template_id or nif
    tpl = j.get("template_id") or j.get("nif") or "unknown_template"
    debug = j.get("debug_id","debug_unknown")
    outf = OUT_DIR / f"{debug}.json"
    example = {
      "debug_file": debug,
      "template_id": tpl,
      "form": {
        "proveedor": safe_get(j,"proveedor",""),
        "nif": safe_get(j,"nif",""),
        "numero_albaran": safe_get(j,"numero_albaran",""),
        "fecha_albaran": safe_get(j,"fecha_albaran",""),
        "productos": safe_get(j,"productos",[])
      },
      "corrections": j
    }
    json.dump(example, open(outf,'w',encoding='utf-8'), ensure_ascii=False, indent=2)
    print("WROTE", outf)
    count += 1

print("Imported", count, "examples")
