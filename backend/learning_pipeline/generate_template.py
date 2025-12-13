import json
import os
from statistics import mean

CORRECTIONS_DIR = "artifacts_device/persisted_corrections"
TEMPLATE_DIR = "backend/templates"

def load_corrections():
    files = [f for f in os.listdir(CORRECTIONS_DIR) if f.endswith(".json")]
    data = []
    for f in files:
        with open(os.path.join(CORRECTIONS_DIR, f)) as fh:
            data.append(json.load(fh))
    return data

def build_templates():
    corrections = load_corrections()
    grouped = {}

    for c in corrections:
        key = c.get("nif")
        if not key:
            continue
        grouped.setdefault(key, []).append(c)

    os.makedirs(TEMPLATE_DIR, exist_ok=True)
    for nif, items in grouped.items():
        template = {"nif": nif, "fields": {}}

        for item in items:
            for f in item.get("fields", []):
                name = f.get("field")
                coords = f.get("coords", {})
                template["fields"].setdefault(name, {"x": [], "y": [], "w": [], "h": []})
                for k in ["x", "y", "w", "h"]:
                    if k in coords:
                        template["fields"][name][k].append(coords[k])

        # Promediar coordenadas
        for name, vals in template["fields"].items():
            # If there are no values for a coordinate, default to 0
            template["fields"][name] = {k: int(mean(v)) if v else 0 for k, v in vals.items()}

        out = os.path.join(TEMPLATE_DIR, f"{nif}.json")
        with open(out, "w") as fh:
            json.dump(template, fh, indent=2)

        print(f"✅ Plantilla generada: {out}")

if __name__ == "__main__":
    build_templates()
