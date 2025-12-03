#!/usr/bin/env python3
import re
from pathlib import Path
from typing import List, Tuple

PROJECT_ROOT = Path(__file__).resolve().parents[1]
DISABLED = PROJECT_ROOT / 'app' / 'src' / 'main' / 'res_disabled_locales'
FAILED = DISABLED / 'failed_sanitized'

Issue = Tuple[int, str, str]


def parse_gradle_logs():
    results = {}
    if not FAILED.exists():
        return results
    for log in FAILED.glob('*.gradle.log'):
        locale = log.stem.replace('.gradle', '')
        text = log.read_text(encoding='utf-8', errors='ignore')
        keys = set(re.findall(r'string/([A-Za-z0-9_]+)', text))
        summary_lines = [l.strip() for l in text.splitlines() if 'Failed to flatten XML' in l or 'Invalid' in l]
        results[locale] = {
            'keys': sorted(keys),
            'summary': summary_lines[:10],
        }
    return results


def find_issues_in_file(file_path: Path) -> List[Issue]:
    issues: List[Issue] = []
    if not file_path.exists():
        return issues
    text = file_path.read_text(encoding='utf-8', errors='ignore')
    # duplicate prolog
    if text.count('<?xml') > 1:
        issues.append((1, 'duplicate-prolog', 'Más de una cabecera XML detectada'))
    lines = text.splitlines()
    for idx, line in enumerate(lines, start=1):
        # invalid unicode escape: \u not followed by 4 hex digits
        for m in re.finditer(r'\\u([0-9A-Fa-f]{0,3})(?![0-9A-Fa-f])', line):
            issues.append((idx, 'invalid-\\u', line.strip()))
        # literal newline escape
        if re.search(r'\\n', line):
            issues.append((idx, 'literal-\\n', line.strip()))
        # backslash-escaped apostrophe
        if re.search(r"\\'", line):
            issues.append((idx, "escaped-apostrophe", line.strip()))
        # stray backslash (not part of \\n, \\u, \\" or \\')
        for m in re.finditer(r'\\(?![nu"\'\\])', line):
            issues.append((idx, 'stray-backslash', line.strip()))
        # unescaped ampersand
        if re.search(r'&(?!#\d+;|#x[0-9A-Fa-f]+;|[A-Za-z0-9]+;)', line):
            issues.append((idx, 'unescaped-&', line.strip()))
    return issues


def main():
    logs = parse_gradle_logs()
    report_lines = []
    for locale_dir in sorted(DISABLED.glob('values*')):
        if not locale_dir.is_dir():
            continue
        src = locale_dir / 'strings.xml'
        issues = find_issues_in_file(src)
        loc_name = locale_dir.name
        report_lines.append(f'Locale: {loc_name}')
        if loc_name in logs:
            log_info = logs[loc_name]
            if log_info['keys']:
                report_lines.append(f" - Keys reportadas en Gradle: {', '.join(log_info['keys'])}")
            for s in log_info['summary']:
                report_lines.append(f" - Gradle: {s}")
        if not issues:
            report_lines.append(' - Sin problemas obvios detectados por patrón')
        else:
            for (ln, kind, snippet) in issues[:20]:
                report_lines.append(f' - [{kind}] línea {ln}: {snippet[:180]}')
        report_lines.append('')
    output_dir = FAILED
    output_dir.mkdir(parents=True, exist_ok=True)
    out = output_dir / 'report.txt'
    out.write_text('\n'.join(report_lines), encoding='utf-8')
    print(out)


if __name__ == '__main__':
    main()
