import argparse
import json
import re
import shutil
from pathlib import Path
from xml.sax.saxutils import escape

p = argparse.ArgumentParser()
p.add_argument("--root", type=Path, required=True)
p.add_argument("--name", required=True)
p.add_argument("--title", required=True)
p.add_argument("--package", required=True)
p.add_argument("--offset", type=int, default=0)
a = p.parse_args()
if not re.fullmatch(r"[a-z][a-z0-9-]{0,39}", a.name):
    p.error("Invalid module slug")
if not re.fullmatch(r"[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+", a.package):
    p.error("Invalid package name")
if not 0 <= a.offset < 2100000000 or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9 &-]{0,59}", a.title):
    p.error("Invalid offset or app title")

template = Path(__file__).resolve().parent.parent / "assets" / "template-monorepo"
root = a.root.resolve()
registry = root / "apps.json"
module = root / "apps" / a.name
if not registry.exists():
    if root.exists() and any(root.iterdir()):
        p.error("Initialize in an empty directory or use an existing pixel-apk monorepo")
    shutil.copytree(template / "project", root, dirs_exist_ok=True)
apps = json.loads(registry.read_text(encoding="utf-8"))
if module.exists() or any(x["name"] == a.name or x["package"] == a.package for x in apps):
    p.error("App slug or package already exists")
shutil.copytree(template / "module", module)
for path in module.rglob("*"):
    if path.is_file():
        value = path.read_text(encoding="utf-8")
        for old, new in {"@PACKAGE@": a.package, "@TITLE@": escape(a.title), "@OFFSET@": str(a.offset)}.items():
            value = value.replace(old, new)
        path.write_text(value, encoding="utf-8")
apps.append({"name": a.name, "package": a.package, "offset": a.offset})
tmp = registry.with_suffix(".tmp")
tmp.write_text(json.dumps(apps, indent=2) + "\n", encoding="utf-8")
tmp.replace(registry)
print(module)

