#!/usr/bin/env python3
import json,csv,tarfile,time
from pathlib import Path

LQ = Path("artifacts_device/learning_queue_from_corrections")
manifest = Path("artifacts_device/learning_manifest.csv")
rows=[]
for f in sorted(LQ.glob("*.json")):
    j=json.load(open(f,'r',encoding='utf-8'))
    debug=j.get("debug_file")
    tpl=j.get("template_id")
    prod_count=len(j.get("form",{}).get("productos",[]))
    rows.append([debug,tpl,prod_count,str(f)])
with open(manifest,'w',encoding='utf-8',newline='') as fh:
    w=csv.writer(fh)
    w.writerow(["debug_file","template_id","n_productos","path"])
    w.writerows(rows)
ts=time.strftime("%Y%m%d_%H%M%S")
tar_path = Path(f"artifacts_device/learning_package_{ts}.tgz")
with tarfile.open(tar_path,'w:gz') as t:
    if LQ.exists():
        t.add(LQ, arcname="learning_queue")
    t.add(manifest, arcname="manifest.csv")
print("Created", tar_path)
