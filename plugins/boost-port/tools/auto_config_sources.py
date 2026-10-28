#!/usr/bin/env python3
"""JVM oracle from frozen V5 sources. Only package/key binding is changed; no policy rewrite."""
from pathlib import Path
import hashlib
import json
import re

MODULE = Path(__file__).resolve().parents[1]
PACKAGE = 'app.aaps.plugins.boostport.reference.autoconfig'
BLOBS = {'BoostV5AutoConfig': '2720697d21eb55c1ce3f6489c9f4d7ab442f8bbc', 'BoostV5AutoConfigApply': '72305da2a65a1c85d0b77bb702bbe26756718bbb', 'TrancheThresholdDeriver': '3f44dc8070fd7f622ac79408aba8b6d48f44e853', 'ConfirmTrancheController': '1b0e9f306548f650d070516fed901866900d53bb'}
METADATA = {'BoostApsKeyDefaults.json.source': '2f19716142605d6c3002d755d391bcca8924e7178f31e8e988013e6942e2ca04',
            'aps-ui-settings.json': 'be17e8d9679c07f5eb0df4431ed87a02862ddfe4de02009fb2d6a755fbabe987'}


def write_reference(output):
    metadata = {}
    for name, digest in METADATA.items():
        raw = (MODULE / 'port-source' / name).read_bytes()
        if hashlib.sha256(raw).hexdigest() != digest:
            raise ValueError(f'V5 source metadata changed: {name}')
        metadata[name] = json.loads(raw)
    defaults = {e['name']: e for e in metadata['BoostApsKeyDefaults.json.source']['entries']}
    bounds = {e['name']: e for e in metadata['aps-ui-settings.json']['settings']}
    bodies = {}
    for name, blob in BLOBS.items():
        raw = (MODULE / 'port-source/6.2.0' / (name + '.kt.source')).read_bytes()
        if hashlib.sha1(f'blob {len(raw)}\0'.encode() + raw).hexdigest() != blob:
            raise ValueError(f'V5 source blob changed: {name}')
        source = raw.decode().replace('package app.aaps.plugins.aps.openAPSBoostV5', f'package {PACKAGE}')
        source = re.sub(r'^import (app\.aaps\.|org\.json).*\n', '', source, flags=re.M)
        if name == 'BoostV5AutoConfigApply':
            source = source[:source.index("\n/**\n * Auto-config's own string state:")]
        bodies[name] = source
    names = sorted(set(re.findall(r'\bDoubleKey\.(Aps\w+)', bodies['BoostV5AutoConfigApply'])))
    assert len(names) == 9, names
    entries = []
    for name in names:
        d, b = defaults[name], bounds[name]
        entries.append(f'    {name}("{d["key"]}", {float(d["value"])}, {float(b["min"])}, {float(b["max"])}),')
    output.mkdir(parents=True, exist_ok=True)
    key_source = f'package {PACKAGE}\n\ninternal enum class ReferenceDoubleKey(val key: String, val defaultValue: Double, val min: Double, val max: Double) {{\n' + '\n'.join(entries) + '\n}\n'
    (output / 'ReferenceAutoConfigKeys.kt').write_text(key_source)
    engine_raw = (MODULE / 'legacy-snapshot/plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoostV5/DetermineBasalBoostV5.kt.source').read_bytes()
    if hashlib.sha1(f'blob {len(engine_raw)}\0'.encode() + engine_raw).hexdigest() != '16544e5d7d9d12d81a4c9955e4b12d7e68f97fec':
        raise ValueError('V5 source primer constants changed')
    engine = engine_raw.decode()
    constants = re.findall(r'^internal const val PRIMER_DELTA_(?:MIN|FULL) = [0-9.]+$', engine, re.M)
    assert len(constants) == 2
    (output / 'ReferenceAutoConfigConstants.kt').write_text(f'package {PACKAGE}\n' + '\n'.join(constants) + '\n')
    for name, source in bodies.items():
        (output / (name + '.kt')).write_text(re.sub(r'\bDoubleKey\b', 'ReferenceDoubleKey', source))

