"""Fail quickly when a qualified Android string resource lacks a default fallback."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

TRANSLATABLE_TYPES = {"string", "plurals", "string-array"}


def definitions(directory):
    entries = {}
    for source in sorted(directory.glob("*.xml")):
        for entry in ET.parse(source).getroot():
            kind = entry.attrib.get("type") if entry.tag == "item" else entry.tag
            name = entry.attrib.get("name")
            if kind in TRANSLATABLE_TYPES and name:
                entries[(kind, name)] = source
    return entries


def find_missing_defaults(project_root):
    missing = []
    for resource_root in sorted(Path(project_root).glob("*/src/main/res")):
        defaults = definitions(resource_root / "values")
        for qualifier in sorted(resource_root.glob("values-*")):
            for key, source in definitions(qualifier).items():
                if key not in defaults:
                    missing.append((str(source.relative_to(project_root)), key[0], key[1]))
    return missing


def main():
    missing = find_missing_defaults(Path.cwd())
    for source, kind, name in missing:
        print(f"{source}: {kind}/{name} has no default resource", file=sys.stderr)
    if missing:
        raise SystemExit(1)
    print("All qualified string resources have default fallbacks.")


if __name__ == "__main__":
    main()
