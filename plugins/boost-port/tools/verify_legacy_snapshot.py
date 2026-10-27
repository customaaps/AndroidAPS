#!/usr/bin/env python3
"""Verify the archived Boost V6 source set without activating or compiling it."""
from __future__ import annotations

import hashlib
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MODULE = ROOT / "plugins" / "boost-port"
MANIFEST = MODULE / "SOURCE_MANIFEST.md"
SNAPSHOT = MODULE / "legacy-snapshot"
ENTRY = re.compile(r"^([0-9a-f]{40})  (.+)$")


def main() -> int:
    rows = [m.groups() for line in MANIFEST.read_text(encoding="utf-8").splitlines()
            if (m := ENTRY.fullmatch(line))]
    if len(rows) != 114 or len({path for _, path in rows}) != 114:
        raise SystemExit(f"Unexpected manifest count or duplicate paths: {len(rows)}")
    counts = {"kotlin": 0, "tests": 0, "json": 0}
    for expected, relative in rows:
        original = Path(relative)
        if original.is_absolute() or ".." in original.parts:
            raise SystemExit(f"Unsafe source path: {relative}")
        archived = SNAPSHOT / (relative + (".source" if relative.endswith(".kt") else ""))
        if not archived.is_file():
            raise SystemExit(f"Missing archived file: {archived}")
        actual = subprocess.run(
            ["git", "hash-object", "--", str(archived)],
            check=True, text=True, capture_output=True,
        ).stdout.strip()
        if actual != expected:
            raise SystemExit(f"Blob SHA mismatch: {relative}: {actual} != {expected}")
        if relative.endswith(".kt"):
            counts["tests" if "/src/test/" in relative else "kotlin"] += 1
        elif relative.endswith(".json"):
            counts["json"] += 1
        else:
            raise SystemExit(f"Unexpected source kind: {relative}")
    if counts != {"kotlin": 39, "tests": 71, "json": 4}:
        raise SystemExit(f"Unexpected file types: {counts}")
    print(f"Verified {len(rows)} original Git blobs: {counts}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
