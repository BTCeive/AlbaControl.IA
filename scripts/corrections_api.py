#!/usr/bin/env python3
from flask import Flask, request, jsonify, send_file, abort
from pathlib import Path
import json, os, csv, time
from datetime import datetime
import re

OUT_DIR = Path("artifacts_device/persisted_corrections")
OUT_DIR.mkdir(parents=True, exist_ok=True)

app = Flask(__name__)

# API key (can be overridden by env var CORRECTIONS_API_KEY)
API_KEY = os.environ.get("CORRECTIONS_API_KEY", "dev-key")

def require_api_key(func):
    from functools import wraps
    @wraps(func)
    def wrapper(*args, **kwargs):
        key = request.headers.get("X-API-Key") or request.args.get("api_key")
        if not key or key != API_KEY:
            return jsonify({"ok": False, "error": "unauthorized"}), 401
        return func(*args, **kwargs)
    return wrapper

def normalize_nif(nif):
    if not nif:
        return ""
    return re.sub(r'[^A-Za-z0-9]', '', str(nif)).upper()

def valid_iso_date(s):
    if not s:
        return False
    try:
        datetime.fromisoformat(s)
        return True
    except Exception:
        try:
            datetime.strptime(s, "%d/%m/%Y")
            return True
        except Exception:
            return False

def validate_payload(p):
    if not isinstance(p, dict):
        return False, "payload must be a JSON object"
    if not p.get("debug_id"):
        return False, "missing debug_id"
    if not (p.get("template_id") or p.get("nif")):
        return False, "missing template_id or nif"
    if p.get("nif"):
        p["nif"] = normalize_nif(p["nif"])
    if p.get("proveedor"):
        p["proveedor"] = str(p["proveedor"]).strip()
    if p.get("fecha_albaran") and not valid_iso_date(p.get("fecha_albaran")):
        return False, "fecha_albaran must be ISO date YYYY-MM-DD or ISO format"
    prods = p.get("productos")
    if prods is not None:
        if not isinstance(prods, list):
            return False, "productos must be a list"
        for i, row in enumerate(prods):
            if not isinstance(row, dict):
                return False, f"producto at index {i} must be an object"
            if not row.get("descripcion"):
                return False, f"producto at index {i} missing descripcion"
            if "unidades" in row:
                try:
                    float(row["unidades"])
                except Exception:
                    return False, f"producto at index {i} unidades must be numeric"
            if "precio_unitario" in row:
                try:
                    float(row["precio_unitario"])
                except Exception:
                    return False, f"producto at index {i} precio_unitario must be numeric"
    return True, p

@app.route("/api/v1/corrections", methods=["POST"])
@require_api_key
def receive_correction():
    try:
        payload = request.get_json(force=True)
    except Exception as e:
        return jsonify({"ok": False, "error": "invalid json", "detail": str(e)}), 400
    ok, info = validate_payload(payload)
    if not ok:
        return jsonify({"ok": False, "error": info}), 400
    ts = datetime.utcnow().strftime("%Y%m%dT%H%M%SZ")
    debug = info.get("debug_id", "debug_unknown")
    fname = OUT_DIR / f"{debug}_corr_{ts}.json"
    fname.write_text(json.dumps(info, ensure_ascii=False, indent=2), encoding="utf-8")
    return jsonify({"ok": True, "path": str(fname)}), 201

@app.route("/api/v1/health", methods=["GET"])
def health():
    return jsonify({"ok": True, "persisted_dir": str(OUT_DIR)})

@app.route("/api/v1/corrections", methods=["GET"])
@require_api_key
def list_corrections():
    files = sorted(OUT_DIR.glob("*.json"), key=lambda p: p.stat().st_mtime, reverse=True)
    items = []
    for f in files:
        st = f.stat()
        items.append({
            "filename": f.name,
            "path": str(f),
            "size": st.st_size,
            "mtime": int(st.st_mtime)
        })
    return jsonify({"ok": True, "count": len(items), "items": items})

@app.route("/api/v1/corrections/<debug_id>", methods=["GET"])
@require_api_key
def get_correction(debug_id):
    # allow either exact filename or debug_id prefix
    files = sorted(OUT_DIR.glob(f"{debug_id}*.json"), key=lambda p: p.stat().st_mtime, reverse=True)
    if not files:
        return jsonify({"ok": False, "error": "not found"}), 404
    return send_file(str(files[0]), mimetype="application/json", as_attachment=True, download_name=files[0].name)

@app.route("/api/v1/export_manifest", methods=["POST"])
@require_api_key
def export_manifest():
    # regenerate manifest from persisted corrections or from learning_queue if present
    source = request.json.get("source","persisted") if request.is_json else "persisted"
    out_manifest = Path("artifacts_device/learning_manifest.csv")
    rows = []
    # prefer learning_queue_from_corrections if exists
    lq_dir = Path("artifacts_device/learning_queue_from_corrections")
    if lq_dir.exists() and any(lq_dir.glob("*.json")):
        files = sorted(lq_dir.glob("*.json"))
        for f in files:
            j = json.load(open(f, 'r', encoding='utf-8'))
            debug = j.get("debug_file")
            tpl = j.get("template_id")
            prod_count = len(j.get("form",{}).get("productos",[]))
            rows.append([debug, tpl, prod_count, str(f)])
    else:
        files = sorted(OUT_DIR.glob("*.json"))
        for f in files:
            j = json.load(open(f, 'r', encoding='utf-8'))
            debug = j.get("debug_id", f.stem)
            tpl = j.get("template_id") or j.get("nif") or ""
            prod_count = len(j.get("productos", []))
            rows.append([debug, tpl, prod_count, str(f)])
    with open(out_manifest, 'w', encoding='utf-8', newline='') as fh:
        w = csv.writer(fh)
        w.writerow(["debug_file","template_id","n_productos","path"])
        w.writerows(rows)
    return jsonify({"ok": True, "manifest": str(out_manifest), "count": len(rows)}), 200

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5001, debug=False)
