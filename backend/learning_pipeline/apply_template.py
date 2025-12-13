import json
import sys

def apply_template(template_path, ocr_path):
    with open(template_path) as fh:
        template = json.load(fh)
    with open(ocr_path) as fh:
        ocr = json.load(fh)

    words = ocr.get("words", [])
    result = {}

    for field, coords in template.get("fields", {}).items():
        x, y, w, h = coords.get("x",0), coords.get("y",0), coords.get("w",0), coords.get("h",0)
        candidates = [
            w for w in words
            if x <= w.get("x",0) <= x+w and y <= w.get("y",0) <= y+h
        ]
        result[field] = candidates[0].get("text") if candidates else ""

    print(json.dumps(result, indent=2))

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: apply_template.py <template.json> <ocr.json>")
        sys.exit(2)
    apply_template(sys.argv[1], sys.argv[2])
