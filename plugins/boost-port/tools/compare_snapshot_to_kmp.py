#!/usr/bin/env python3
"""Source-to-compiled KMP migration inventory (no APS registration or dosing)."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path

MODULE = Path(__file__).resolve().parents[1]
MANIFEST = MODULE / "SOURCE_MANIFEST.md"
SOURCE = MODULE / "legacy-snapshot"
MAIN = MODULE / "src" / "commonMain" / "kotlin"
TEST = MODULE / "src" / "commonTest" / "kotlin"
ENTRY = re.compile(r"^([a-f0-9]{40})  (.+)$")
ENGINE = {"DetermineBasalBoost.kt", "DetermineBasalBoostV5.kt",
          "OpenAPSBoostPlugin.kt", "OpenAPSBoostV5Plugin.kt"}


def entries():
    for line in MANIFEST.read_text(encoding="utf-8").splitlines():
        matched = ENTRY.fullmatch(line)
        if matched:
            yield matched.group(1), matched.group(2)


def compute():
    main_paths = list(MAIN.rglob("*.kt"))
    test_paths = list(TEST.rglob("*.kt"))
    by_name = {}
    for path in main_paths:
        by_name.setdefault(path.name, []).append(path)
    by_test = {}
    for path in test_paths:
        by_test.setdefault(path.name, []).append(path)
    output = []
    for blob, path in entries():
        if not path.endswith(".kt"):
            continue
        name = Path(path).name
        is_test = "/src/test/" in path
        matches = (by_test if is_test else by_name).get(name, [])
        source_path = SOURCE / (path + ".source")
        source_content = source_path.read_bytes()
        source_hash = hashlib.sha256(source_content).hexdigest()
        identical = [
            str(p.relative_to(MODULE)) for p in matches
            if hashlib.sha256(p.read_bytes()).hexdigest() == source_hash
        ]
        kind = "legacy-test" if is_test else ("engine-boundary" if name in ENGINE else "implementation")
        output.append({
            "source": path,
            "git_blob": blob,
            "kind": kind,
            "compiled_same_name": [str(p.relative_to(MODULE)) for p in matches],
            "byte_identical": identical,
            "status": "identical" if identical else "adapted-or-unverified" if matches else "not-in-kmp",
        })
    return output


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, help="Optional path for JSON report")
    args = parser.parse_args()
    rows = compute()
    counts = {kind: sum(x["status"] == kind for x in rows)
              for kind in ("identical", "adapted-or-unverified", "not-in-kmp")}
    engine = [x for x in rows if x["kind"] == "engine-boundary"]
    report = {"disclaimer": "Static name and byte comparison only; does not prove behavioral or clinical parity",
              "source_kotlin_total": len(rows), "counts": counts,
              "engine_boundaries": engine, "files": rows}
    body = json.dumps(report, indent=2, ensure_ascii=False, sort_keys=True) + "\n"
    if args.out:
        args.out.write_text(body, encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "files"},
                     indent=2, ensure_ascii=False))
    if len(rows) != 110 or len(engine) != 4:
        raise SystemExit("Unexpected source inventory; inspect the snapshot.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
