#!/usr/bin/env python3
"""Verify the integration key defaults against the pinned source-only selection."""
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
archive = ROOT / 'port-source/BoostApsKeyDefaults.json.source'
assert hashlib.sha256(archive.read_bytes()).hexdigest() == '2f19716142605d6c3002d755d391bcca8924e7178f31e8e988013e6942e2ca04'
snapshot = json.loads(archive.read_text())
assert snapshot['sourceCommit'] == 'bc02e4c49f2b9bb5d50a5938b9a09884c76140cd'
text = (ROOT / 'src/commonMain/kotlin/app/aaps/plugins/boostport/aps/BoostApsSettingKeys.kt').read_text()
actual = {}
for name, kind, key, value, private in re.findall(
    r'val (ApsBoost\w+) = (\w+)\("([^"]+)", ("[^"]*"|true|false|[0-9]+(?:\.[0-9]+)?)(, exportable = false)?\)', text
):
    actual[name] = dict(name=name, kind=kind, key=key, value=json.loads(value), exportable=not private)
assert actual == {row['name']: row for row in snapshot['entries']}
assert len({row['key'] for row in actual.values()}) == len(actual)
assert all('V2' not in name and 'V7' not in name for name in actual)
assert 'StringValue("boost_aps_session_v1", "")' in text
print(f'Verified {len(actual)} source Boost integration key names/defaults/export flags; no V2/V7')
