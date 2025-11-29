import sys,re
from pathlib import Path
p=Path(sys.argv[1])
s=p.read_text(encoding='utf-8')
items=[]
for m in re.finditer(r"<string\b([^>]*)>(.*?)</string>",s,flags=re.S):
    attrs=m.group(1)
    val=m.group(2).strip()
    # find name attr
    nm=re.search(r"name=(?:\"([^\"]+)\"|'([^']+)')",attrs)
    if not nm:
        continue
    name=nm.group(1) or nm.group(2)
    # normalize escapes
    val=val.replace('\\&apos;','\'')
    val=val.replace('&apos;', "'")
    val=val.replace('\\n','\n')
    # strip wrapping quotes
    if val.startswith("'") and val.endswith("'"):
        val=val[1:-1]
    # escape XML special
    val=val.replace('&','&amp;').replace('<','&lt;').replace('>','&gt;')
    items.append((name,val))
# write cleaned resources
out='<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
for name,val in items:
    out += f'    <string name="{name}">{val}</string>\n'
out += '</resources>\n'
Path(sys.argv[2]).write_text(out,encoding='utf-8')
print('WROTE',sys.argv[2])
