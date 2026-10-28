"""Source-pinned diagnostic arithmetic and current guard breadcrumbs."""
from pathlib import Path
import hashlib
ROOT = Path(__file__).resolve().parents[1]
ARCHIVE = ROOT / 'legacy-snapshot/plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost'
PACKAGE = 'app.aaps.plugins.boostport.aps'
PORT = ROOT / 'src/commonMain/kotlin/app/aaps/plugins/boostport/aps/BoostVwaTddDiagnostic.kt'

def vwa(reference=False):
    p = ARCHIVE / 'BoostVwaTddShadow.kt.source'
    raw = p.read_bytes()
    assert hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest() == 'a0787acec53c0411b5579f5b6ddc39e712d1b2da'
    s = raw.decode().replace('package app.aaps.plugins.aps.openAPSBoost', 'package ' + ('app.aaps.plugins.boostport.reference.diagnostic' if reference else PACKAGE), 1)
    s = s.replace('class BoostVwaTddShadow(', 'internal class ' + ('ReferenceVwaTddShadow' if reference else 'BoostVwaTddDiagnostic') + '(')
    s = s.replace('fun warmFromHistory(nowMs: Long, totalBetween: (Long, Long) -> Double?)', 'suspend fun warmFromHistory(nowMs: Long, totalBetween: suspend (Long, Long) -> Double?)')
    if not reference:
        s = s.replace('import kotlin.math.max', 'import app.aaps.plugins.aps.openAPSBoost.activity.SourceJsonObject\nimport app.aaps.plugins.aps.openAPSBoost.activity.SourceJsonArray\nimport app.aaps.plugins.aps.openAPSBoost.activity.activityFormat\nimport kotlin.math.max')
        s = s.replace('Math.floorDiv(nowMs - anchorHour * 60L * 60 * 1000, DAY_MS)', '(nowMs - anchorHour * 60L * 60 * 1000).floorDiv(DAY_MS)')
        s = s.replace('String.format("%.2f", v)', 'activityFormat("%.2f", v)')
        s = s.replace('org.json.JSONObject', 'SourceJsonObject')
        for name in ('curve', 'dayBuckets'):
            s = s.replace('org.json.JSONArray(' + name + '.toList())', 'SourceJsonArray().also { a -> ' + name + '.forEach { a.put(it) } }')
    return s

def guard_traces(s):
    # Only attach source curve names; no ISF, CR, prediction or dose expression changes.
    s = s.replace('var minGuardBG: Double = bg', 'var minGuardBG: Double = bg\n        var minGuardBGSource = "IOB"', 1)
    for expression, label in (
        ('fractionCarbsLeft * minCOBGuardBG + (1 - fractionCarbsLeft) * minUAMGuardBG', 'COB+UAM'),
        ('minCOBGuardBG', 'COB'), ('minUAMGuardBG', 'UAM'), ('minIOBGuardBG', 'IOB')):
        line = 'minGuardBG = ' + expression + '\n'
        assert s.count(line) == 1
        s = s.replace(line, line.rstrip() + '; minGuardBGSource = "' + label + '"\n', 1)
    s = s.replace('rT.minGuardBG = minGuardBG', 'rT.minGuardBG = minGuardBG\n        rT.minGuardBGSource = minGuardBGSource', 1)
    for suffix in (', IOBpredBG', ' projected below', ' < ${convert_bg(threshold)}'):
        old = 'minGuardBG ${convert_bg(minGuardBG)}' + suffix
        assert s.count(old) == 1
        s = s.replace(old, 'minGuardBG ${convert_bg(minGuardBG)} ($minGuardBGSource)' + suffix, 1)
    return s

def isf_reference():
    p = ARCHIVE / 'BoostIsfShadow.kt.source'
    raw = p.read_bytes()
    assert hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest() == 'ece9e8f4d81663a05f9aa42f2ba3f9983d2faa3d'
    s = raw.decode().replace('package app.aaps.plugins.aps.openAPSBoost', 'package app.aaps.plugins.boostport.reference.diagnostic', 1)
    for dep in ('app.aaps.core.interfaces.logging.AAPSLogger', 'app.aaps.core.interfaces.logging.LTag', 'app.aaps.core.keys.StringKey', 'app.aaps.core.keys.interfaces.Preferences', 'app.aaps.plugins.aps.getBoostDosing', 'javax.inject.Inject', 'javax.inject.Singleton'):
        s = s.replace('import ' + dep + '\n', '')
    s = s.replace('@Singleton\nclass BoostIsfShadow @Inject constructor(\n    private val preferences: Preferences,\n    private val aapsLogger: AAPSLogger\n)', 'internal class ReferenceIsfShadow(private val loadState: () -> String, private val saveState: (String) -> Unit)')
    s = s.replace('preferences.getBoostDosing(StringKey.ApsBoostIsfShadowState)', 'loadState()')
    s = s.replace('preferences.put(StringKey.ApsBoostIsfShadowState, j.toString())', 'saveState(j.toString())')
    s = '\n'.join(line for line in s.split('\n') if 'aapsLogger.' not in line)
    return s


def write_reference(output):
    p = output / 'app/aaps/plugins/boostport/reference/diagnostic'
    p.mkdir(parents=True, exist_ok=True)
    (p / 'ReferenceVwaTddShadow.kt').write_text(vwa(reference=True))
    (p / 'ReferenceIsfShadow.kt').write_text(isf_reference())
