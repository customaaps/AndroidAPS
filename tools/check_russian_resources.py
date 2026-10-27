#!/usr/bin/env python3
"""Audit Russian Android text resources without Gradle or third-party packages.

Run from any directory. --english lists identical Latin text that needs either a
translation or an exact, reviewed entry in russian_resource_exceptions.json.
The default run fails on missing text, malformed/empty translations, type changes,
format-argument changes and unreviewed identical English. Reference-only arrays
can inherit their default declaration when all referenced strings are localized.
Names, wire values and abbreviated units are documented exact-value exceptions;
translatable=false and non-text resources are outside translation coverage.
"""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
TEXT_TYPES = {"string", "plurals", "string-array"}
SKIP = {".git", "build", "legacy-snapshot", "legacy-source", ".gradle"}
FORMAT = re.compile(r"%(?:(\d+)\$)?[-#+0,(<]*\d*(?:\.\d+)?([bBhHsScCdoxXeEfgGaAtT%nt])")
LATIN = re.compile(r"[A-Za-z]{2,}")
CYRILLIC = re.compile(r"[\u0400-\u04ff]")


def text(node: ET.Element) -> str:
    return "".join(node.itertext()).strip().strip('"')


def formats(value: str) -> Counter:
    result = Counter()
    ordinal = 0
    previous = None
    for match in FORMAT.finditer(value):
        position, kind = match.groups()
        if kind in ("%", "n"):
            continue
        if position is None:
            if "<" in match.group(0):
                position = previous
            else:
                ordinal += 1
                position = str(ordinal)
        previous = position
        result[(position, kind)] += 1
    return result


def default_folder(name: str) -> bool:
    if name == "values":
        return True
    if not name.startswith("values-"):
        return False
    qualifiers = name.split("-")[1:]
    return not any(part.startswith("b+") or (re.fullmatch(r"[a-z]{2,3}", part) and part != "car")
                   for part in qualifiers)


def load(folder: Path, errors: list[str]) -> dict[str, ET.Element]:
    result = {}
    for file in sorted(folder.glob("*.xml")):
        try:
            nodes = ET.parse(file).getroot()
        except ET.ParseError as exc:
            errors.append(f"{file.relative_to(ROOT)}: malformed XML: {exc}")
            continue
        for node in nodes:
            name = node.get("name")
            if node.tag not in TEXT_TYPES or not name:
                continue
            if name in result:
                errors.append(f"{file.relative_to(ROOT)}: duplicate text resource {name}")
            result[name] = node
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--english", action="store_true", help="print identical Latin candidates, including reviewed exceptions")
    parser.add_argument("--json", action="store_true", help="emit machine-readable summary")
    args = parser.parse_args()
    errors: list[str] = []
    exceptions = json.loads(Path(__file__).with_name("russian_resource_exceptions.json").read_text())
    exception_map = {item["resource"]: item for item in exceptions}
    folders = sorted(p for p in ROOT.rglob("res/values*") if p.is_dir() and default_folder(p.name) and not SKIP.intersection(p.parts))
    defaults = {folder: load(folder, errors) for folder in folders}
    russian = {folder.parent: load(folder.parent / "values-ru-rRU", errors) for folder in folders}
    # References may be owned by another dependency module. AAPT resolves their
    # ownership; this audit only checks that a Russian translation exists.
    all_ru = {name for values in russian.values() for name in values}
    all_untranslated = {name for values in defaults.values() for name, node in values.items() if node.get("translatable") == "false"}
    total = localized = inherited = excluded = 0
    english = []
    for folder, base in defaults.items():
        ru = russian[folder.parent]
        qualifier = folder.name.removeprefix("values")
        if qualifier:
            ru = {**ru, **load(folder.parent / ("values-ru-rRU" + qualifier), errors)}
        for name, node in base.items():
            if node.get("translatable") == "false":
                excluded += 1
                continue
            total += 1
            identity = f"{folder.relative_to(ROOT)}/{name}"
            translation = ru.get(name)
            expected = text(node)
            if translation is None:
                exception = exception_map.get(identity)
                if exception and exception.get("text") == expected and exception.get("reason"):
                    inherited += 1
                    continue
                items = list(node) if node.tag == "string-array" else [node]
                def inherited_item(item: ET.Element) -> bool:
                    value = text(item)
                    if value.startswith("@android:string/"):
                        return True
                    if value.startswith("@string/"):
                        return value[8:] in all_ru or value[8:] in all_untranslated
                    return not LATIN.search(value) and not CYRILLIC.search(value)
                if all(inherited_item(item) for item in items):
                    inherited += 1
                    continue
                errors.append(f"{identity}: missing Russian {node.tag}")
                continue
            localized += 1
            if node.tag != translation.tag:
                errors.append(f"{identity}: type {node.tag} changed to {translation.tag}")
                continue
            if node.tag == "plurals":
                en_items = {n.get("quantity"): n for n in node}
                ru_items = {n.get("quantity"): n for n in translation}
                if "other" not in ru_items:
                    errors.append(f"{identity}: Russian plurals has no other quantity")
                pairs = [(en_items.get(q, en_items.get("other")), n) for q, n in ru_items.items()]
            elif node.tag == "string-array":
                if len(node) != len(translation):
                    errors.append(f"{identity}: array length {len(node)} changed to {len(translation)}")
                pairs = list(zip(node, translation))
            else:
                pairs = [(node, translation)]
            for source, translated in pairs:
                if source is None:
                    continue
                if text(source) and not text(translated):
                    errors.append(f"{identity}: empty Russian text")
                if node.get("formatted") != "false" and formats(text(source)) != formats(text(translated)):
                    errors.append(f"{identity}: format arguments differ: {text(source)!r} -> {text(translated)!r}")
            actual = text(translation)
            if expected == actual and LATIN.search(expected) and not expected.startswith(("@", "https://", "http://")):
                exception = exception_map.get(identity)
                reviewed = exception is not None and exception.get("text") == expected and bool(exception.get("reason"))
                english.append({"resource": identity, "text": expected, "reviewed": reviewed})
                if not reviewed:
                    errors.append(f"{identity}: identical Latin text needs translation or an exact documented exception: {expected!r}")
    literal_layouts = []
    android_ns = "{http://schemas.android.com/apk/res/android}"
    for file in sorted(ROOT.rglob("res/layout*/*.xml")):
        if SKIP.intersection(file.parts):
            continue
        try:
            nodes = ET.parse(file).getroot().iter()
        except ET.ParseError as exc:
            errors.append(f"{file.relative_to(ROOT)}: malformed XML: {exc}")
            continue
        for node in nodes:
            for attr in ("text", "textOn", "textOff", "hint", "contentDescription", "title", "summary", "prompt", "label"):
                value = node.get(android_ns + attr, "")
                if value and not value.startswith(("@", "?")) and LATIN.search(value):
                    identity = f"{file.relative_to(ROOT)}#{attr}"
                    exception = exception_map.get(identity)
                    if not (exception and exception.get("text") == value and exception.get("reason")):
                        literal_layouts.append({"file": str(file.relative_to(ROOT)), "attribute": attr, "text": value})
    summary = {"text_resources": total, "russian_resources": localized, "safe_inherited": inherited,
               "translatable_false": excluded, "reviewed_identical": sum(x["reviewed"] for x in english),
               "errors": errors, "layout_literals": literal_layouts}
    if args.english:
        summary["identical_latin"] = english
    if args.json:
        print(json.dumps(summary, ensure_ascii=False, indent=2))
    else:
        print(f"Russian resources: {localized}/{total}; inherited language-independent/reference resources: {inherited}; translatable=false: {excluded}; reviewed identical text: {summary['reviewed_identical']}")
        for error in errors:
            print("ERROR:", error)
        for item in literal_layouts:
            print("LAYOUT LITERAL:", item)
        if args.english:
            for item in english:
                print("REVIEWED" if item["reviewed"] else "UNREVIEWED", item["resource"], repr(item["text"]))
        print(f"{len(errors)} error(s); {len(literal_layouts)} layout literal(s) for review")
    return 1 if errors or literal_layouts else 0


if __name__ == "__main__":
    sys.exit(main())
