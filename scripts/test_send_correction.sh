#!/usr/bin/env bash
set -euo pipefail
API=${API_URL:-http://127.0.0.1:5001/api/v1/corrections}
cat <<'JSON' | curl -sS -X POST -H "Content-Type: application/json" -d @- "$API" | jq .
{
  "debug_id":"debug_1765357529425_a84354174",
  "user_id":"lorenzo",
  "timestamp":"2025-12-12T09:00:00Z",
  "template_id":"tpl_A84354174_v1",
  "proveedor":"BIMBO DONUTS IBERIA, S.A.U",
  "nif":"A84354174",
  "numero_albaran":"000123",
  "fecha_albaran":"2025-12-11",
  "productos":[
    {"descripcion":"Donut A","unidades":4,"precio_unitario":0.50,"importe_linea":2.00}
  ],
  "field_id":"etProveedor",
  "text_corrected":"BIMBO DONUTS IBERIA, S.A.U",
  "confidence_before":0.12,
  "bbox":[0.05,0.02,0.60,0.08],
  "crop_path":"artifacts_device/crops/preproc_1765357392077.png",
  "notes":"prueba via api"
}
JSON
