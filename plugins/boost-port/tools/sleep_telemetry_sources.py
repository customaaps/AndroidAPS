#!/usr/bin/env python3
"""Original-source JVM oracle for the sleep/HR upload block and RT wire fields."""
import hashlib
from pathlib import Path
import re

MODULE = Path(__file__).resolve().parents[1]
PACKAGE = 'app.aaps.plugins.boostport.reference.sleeptelemetry'
RT_BLOB = 'ac553e37db44d2a61c098405bba71dc33a81f7a5'


def write_reference(output):
    # Canonical Git text (LF), including on a CRLF checkout.
    raw = (MODULE / 'port-source/RT.kt.source').read_text(encoding='utf-8').encode('utf-8')
    assert hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest() == RT_BLOB, 'Original RT archive changed'
    rt = raw.decode()
    fields = rt[rt.index('    // HR telemetry\n'):rt.index('    // Activity-load shadow')]
    names = re.findall(r'var (\w+):', fields)
    assert len(names) == 15
    plugin = (MODULE / 'legacy-snapshot/plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost/OpenAPSBoostPlugin.kt.source').read_text()
    body = plugin[plugin.index('                it.sleepState ='):plugin.index('                // F4 phone side')]
    body = body.replace('                hrLearnedDaytimeBpmCached = agg.daytimeHrBpm\n', '')
    prefix = '''package PACKAGE

import app.aaps.core.data.model.HR
import app.aaps.plugins.boostport.reference.Round
import app.aaps.plugins.boostport.reference.activity.SleepStateDetector
import app.aaps.plugins.boostport.reference.activity.SleepHistoryTracker
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class SourceSleepTelemetry(
FIELDS
) {
    fun toJson(): JsonObject = buildJsonObject {
JSON_ENTRIES
    }
}

internal object ReferenceSleepTelemetry {
    val fields: Set<String> = setOf(NAMES)
    fun calculate(state: SleepStateDetector.State, agg: SleepHistoryTracker.Aggregate,
        hrReadingsForSleep: List<HR>, now: Long): SourceSleepTelemetry {
        val sleepResult = object { val newState = state }
        return SourceSleepTelemetry().also {
BODY
        }
    }
}
'''.replace('PACKAGE', PACKAGE).replace('FIELDS', fields).replace('NAMES', ', '.join('"' + n + '"' for n in names)).replace('BODY', body).replace('JSON_ENTRIES', '\n'.join('        ' + n + '?.let { put(\"' + n + '\", it) }' for n in names))
    out = output / Path(PACKAGE.replace('.', '/'))
    out.mkdir(parents=True, exist_ok=True)
    (out / 'ReferenceSleepTelemetry.kt').write_text(prefix)
    resolver = (MODULE / 'legacy-snapshot/plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost/HrSourceResolver.kt.source').read_text()
    (out / 'HrSourceResolver.kt').write_text(resolver.replace('package app.aaps.plugins.aps.openAPSBoost', 'package ' + PACKAGE, 1))
