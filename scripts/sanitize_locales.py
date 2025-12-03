#!/usr/bin/env python3
"""
Sanitize locale resource backups in `app/src/main/res_disabled_locales/`.

For each `values-xx` folder found there, this script:
 - reads the original `strings.xml` (best-effort extraction of first <resources>..</resources> block),
 - sanitizes each string value:
     - convierte `\n` o saltos reales a `&#10;`
     - convierte `\\uXXXX` a carácter Unicode real (no a entidades hex)
     - normaliza apóstrofos ASCII `'` a tipográfico `’` (U+2019), especialmente si hay placeholders `%`
     - escapa `&` sueltos a `&amp;` (manteniendo entidades válidas)
 - writes a safe fallback into `app/src/main/res/values-xx/strings.xml` (backing up any existing target),
 - runs `./gradlew :app:mergeDebugResources` and records success/failure. On failure, it restores the backup and leaves the original in the disabled folder for manual review.

Usage: run from project root:
    ./scripts/sanitize_locales.py

"""
import os
import re
import shutil
import subprocess
from pathlib import Path
from xml.etree import ElementTree as ET

PROJECT_ROOT = Path(__file__).resolve().parents[1]
DISABLED = PROJECT_ROOT / 'app' / 'src' / 'main' / 'res_disabled_locales'
RES = PROJECT_ROOT / 'app' / 'src' / 'main' / 'res'
GRADLE_CMD = ['./gradlew', ':app:mergeDebugResources', '--stacktrace']


def extract_resources_block(text):
    # Best-effort: find first '<resources' and the matching last '</resources>' and return that block
    start = text.find('<resources')
    end = text.rfind('</resources>')
    if start == -1 or end == -1:
        return None
    return text[start:end + len('</resources>')]


def _normalize_apostrophes(text: str) -> str:
    # Si hay placeholders, evitar secuencias de escape peligrosas usando U+2019
    if "'" in text:
        return text.replace("'", "’")
    return text


def _decode_unicode_escapes(text: str) -> str:
    # Reemplaza \uFFFF por el carácter Unicode correspondiente
    return re.sub(r"\\u([0-9A-Fa-f]{4})", lambda m: chr(int(m.group(1), 16)), text)


def sanitize_value(s):
    if s is None:
        return s
    # Unificar finales de línea
    s = s.replace('\r\n', '\n').replace('\r', '\n')
    # Convertir secuencias \n a salto de línea entidad XML
    s = s.replace('\\n', '&#10;')
    # Convertir saltos reales a entidad también (para robustez con aapt2)
    s = s.replace('\n', '&#10;')
    # Decodificar \uFFFF a carácter real
    s = _decode_unicode_escapes(s)
    # Normalizar apóstrofos a U+2019
    s = _normalize_apostrophes(s)
    # Escape ampersands that are not part of an entity (numeric or named), keep valid entities
    s = re.sub(r'&(?!(#\d+;|#x[0-9A-Fa-f]+;|[A-Za-z0-9]+;))', '&amp;', s)
    return s


def parse_and_sanitize(file_path):
    text = file_path.read_text(encoding='utf-8')
    block = extract_resources_block(text)
    if not block:
        # fallback: try to parse whole text
        block = text
    # Ensure well-formed by wrapping with prolog if missing
    xml_text = block
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        # Try to be more tolerant: remove xml PIs except the first, strip bad characters
        # As last resort, wrap each <string> manually
        strings = re.findall(r'<string[^>]*>.*?</string>', xml_text, flags=re.DOTALL)
        root = ET.Element('resources')
        for s in strings:
            try:
                node = ET.fromstring(s)
                root.append(node)
            except ET.ParseError:
                # crude parse: extract name and inner text
                m = re.match(r'<string\s+name="([^\"]+)"[^>]*>(.*)</string>', s, flags=re.DOTALL)
                if m:
                    name, inner = m.group(1), m.group(2)
                    node = ET.Element('string', {'name': name})
                    node.text = inner
                    root.append(node)
    # Sanitize each string element
    for child in list(root):
        if child.tag == 'string':
            val = child.text or ''
            child.text = sanitize_value(val)
    return root


def write_resource(root, target_path):
    target_path.parent.mkdir(parents=True, exist_ok=True)
    # pretty print minimal
    xml = ET.tostring(root, encoding='utf-8')
    prolog = b'<?xml version="1.0" encoding="utf-8"?>\n'
    target_path.write_bytes(prolog + xml)


def run_gradle_merge():
    proc = subprocess.run(GRADLE_CMD, cwd=PROJECT_ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    return (proc.returncode == 0, proc.stdout)


def main():
    if not DISABLED.exists():
        print('No disabled locales folder found at', DISABLED)
        return 1

    results = []
    for child in DISABLED.iterdir():
        if not child.is_dir():
            continue
        if not child.name.startswith('values'):
            continue
        locale = child.name  # e.g., values-tr or a backup like values-tr_auto_move
        print('\nProcessing', locale)
        src = child / 'strings.xml'
        if not src.exists():
            print(' - no strings.xml in', child, '; skipping')
            results.append((locale, 'no-strings', 'skipped'))
            continue

        try:
            root = parse_and_sanitize(src)
        except Exception as e:
            print(' - parse failed:', e)
            results.append((locale, 'parse-error', str(e)))
            continue

        # Normalize target resource directory name: strip any suffixes like _auto_move
        m = re.match(r'^(values(?:-[A-Za-z0-9]+(?:-r[A-Za-z0-9]+)?)?)', locale)
        if m:
            safe_locale = m.group(1)
        else:
            # fallback: if folder is 'values-xx_extra', take first hyphen chunk
            parts = locale.split('_')[0].split('-')
            if len(parts) >= 2:
                safe_locale = 'values-' + parts[1]
            else:
                safe_locale = 'values'

        target_dir = RES / safe_locale
        target_file = target_dir / 'strings.xml'

        # Backup existing target if any
        backup = None
        if target_file.exists():
            backup = DISABLED / f'{locale}.pre_sanitize_backup.xml'
            shutil.copy2(target_file, backup)
            print(' - backed up existing', target_file, '->', backup)

        # Write sanitized
        print(' - writing sanitized to', target_file)
        write_resource(root, target_file)

        # Run gradle merge
        print(' - running gradle merge...')
        ok, gradle_out = run_gradle_merge()
        if ok:
            print(' - merge OK; locale reintroduced:', locale)
            results.append((locale, 'ok', 'reintroduced'))
        else:
            print(' - merge FAILED; restoring backup and moving sanitized aside')
            # restore backup or remove target
            if backup and backup.exists():
                shutil.copy2(backup, target_file)
            else:
                try:
                    target_file.unlink()
                except Exception:
                    pass
            # Save sanitized version and gradle output for diagnosis
            failed_dir = DISABLED / 'failed_sanitized'
            failed_dir.mkdir(parents=True, exist_ok=True)
            sanitized_save = failed_dir / f'{locale}.sanitized.xml'
            try:
                shutil.copy2(target_file, sanitized_save)
            except Exception:
                pass
            log_file = failed_dir / f'{locale}.gradle.log'
            try:
                log_file.write_text(gradle_out, encoding='utf-8')
            except Exception:
                pass
            # leave original in disabled for manual review
            results.append((locale, 'merge-fail', 'left-disabled'))

    print('\nSummary:')
    for r in results:
        print(' -', r[0], r[1], r[2])
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
