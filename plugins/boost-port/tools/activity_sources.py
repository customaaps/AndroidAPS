#!/usr/bin/env python3
"""Mechanically port the source activity/sleep/learning arithmetic and retain a JVM oracle."""
import argparse
import hashlib
import textwrap
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ARCHIVE = ROOT / 'legacy-snapshot/plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost'
PORT = ROOT / 'src/commonMain/kotlin/app/aaps/plugins/aps/openAPSBoost/activity'
PACKAGE = 'app.aaps.plugins.aps.openAPSBoost.activity'
REFERENCE = 'app.aaps.plugins.boostport.reference.activity'
NAMES = ('HrActivityCalculator', 'SleepStateDetector', 'SleepHistoryTracker', 'DailyStepHistoryTracker', 'MealTimeLearner', 'WearStepSource')
CURRENT_SLEEP = ROOT / 'port-source/6.2.0'
# Exact upstream blobs at tim2000s/Boost-in-AAPS_3.4:3f75d6a3; the original archive stays frozen.
SLEEP_BLOBS = {
    'SleepStateDetector.kt.source': '415f3fbba53e0f68ae2056ccd0ea40cf22acc7e1',
    'OpenAPSBoostPlugin.kt.source': '85b34774c353c652dea091ec26cb7586eadf5045',
    'SleepLieInWakeTest.kt.source': '4dd15130da14b22ee3a9fd1519c02a0320d6b466',
    'StepFeed.kt.source': '50beab9dd2d2bb8312cb95e9926501c18351a75b',
}

def current_sleep_source():
    for filename, expected in SLEEP_BLOBS.items():
        raw = (CURRENT_SLEEP / filename).read_bytes()
        actual = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
        if actual != expected:
            raise ValueError('Upstream sleep blob differs: ' + filename)
    return (CURRENT_SLEEP / 'SleepStateDetector.kt.source').read_text()

def sleep_wake_test():
    text = (CURRENT_SLEEP / 'SleepLieInWakeTest.kt.source').read_text()
    text = text.replace('package app.aaps.plugins.aps.openAPSBoost', 'package app.aaps.plugins.boostport.activity', 1)
    text = text.replace('app.aaps.plugins.aps.openAPSBoost.SleepStateDetector', PACKAGE + '.SleepStateDetector')
    return text.replace('import app.aaps.core.data.model.HR', 'import app.aaps.core.data.model.HR\nimport ' + PACKAGE + '.SleepStateDetector', 1)

def transform(name, reference=False):
    text = current_sleep_source() if name == 'SleepStateDetector' else (ARCHIVE / (name + '.kt.source')).read_text()
    package = REFERENCE if reference else PACKAGE
    text = text.replace('package app.aaps.plugins.aps.openAPSBoost', 'package ' + package, 1)
    text = text.replace('object ' + name + ' {', 'internal object ' + name + ' {', 1)
    text = text.replace('import app.aaps.plugins.aps.openAPSBoost.DailyStepHistoryTracker.',
                        'import ' + package + '.DailyStepHistoryTracker.')
    if reference:
        # The JVM Android-org.json test artifact exposes raw Java iterators.
        text = text.replace('.keys()', '.keys().asSequence().map { it as String }.iterator()')
    if not reference:
        text = text.replace('String.format(', 'activityFormat(')
        text = text.replace('.removeIf {', '.removeAll {')
        text = text.replace('perSlot.getOrDefault(slot, 0)', '(perSlot[slot] ?: 0)')
        text = text.replace('m.getOrDefault(slot, 0)', '(m[slot] ?: 0)')
        if name in ('DailyStepHistoryTracker', 'WearStepSource'):
            text = text.replace('\n\nimport ', '\n\nimport app.aaps.plugins.aps.openAPSBoost.StepSourceResolver\nimport ', 1)
        text = text.replace('import org.json.JSONObject', 'import ' + PACKAGE + '.SourceJsonObject as JSONObject')
        text = text.replace('import org.json.JSONArray', 'import ' + PACKAGE + '.SourceJsonArray as JSONArray')
        text = text.replace('Math.PI', 'kotlin.math.PI')
        text = text.replace('Math.round(WAKE_HR_SUSTAIN_MINUTES / spacing).toInt()',
                            'kotlin.math.floor(WAKE_HR_SUSTAIN_MINUTES / spacing + 0.5).toInt()')
    return text


def eligibility(reference=False):
    current_sleep_source()  # independently pinned upstream plugin and StepFeed
    source = (CURRENT_SLEEP / 'OpenAPSBoostPlugin.kt.source').read_text()
    start = source.index('        internal fun boostGateOpen(')
    end = source.index('        /**\n         * Why the learned pre-meal target', start)
    body = textwrap.dedent(source[start:end]).rstrip()
    package = REFERENCE if reference else PACKAGE
    return 'package ' + package + '\n\n/** Pure, exact source 6.2 gate and boundary-exit signals. */\ninternal object BoostEligibilityPolicy {\n' + body + '\n}\n'

def step_feed(reference=False):
    current_sleep_source()
    text = (CURRENT_SLEEP / 'StepFeed.kt.source').read_text()
    package = REFERENCE if reference else 'app.aaps.plugins.aps.openAPSBoost'
    text = text.replace('package app.aaps.plugins.aps.openAPSBoost', 'package ' + package, 1).replace('\nobject StepFeed {', '\ninternal object StepFeed {', 1)
    if not reference:
        # Preserve established common-port freshness, invalid-count and overflow guards.
        text = text.replace('WearStepSource.FRESH_MS', 'WEAR_FRESH_MS')
        text = text.replace('internal object StepFeed {', 'internal object StepFeed {\n    const val WEAR_FRESH_MS = 12 * 60_000L', 1)
        text = text.replace('wearAgeMs <= WEAR_FRESH_MS', 'wearAgeMs in 0L..WEAR_FRESH_MS')
        text = text.replace('stepsAvailable && currentProfileSwitch == 100 && recentSteps60Min < inactivitySteps',
                            'stepsAvailable && currentProfileSwitch == 100 && recentSteps60Min >= 0 && inactivitySteps > 0 && recentSteps60Min < inactivitySteps')
        text = text.replace('stepsAvailable && nowMs in nightEndMs until (nightEndMs + sleepInMs) && recentSteps60Min < sleepInSteps',
                            'stepsAvailable && recentSteps60Min >= 0 && sleepInSteps > 0 && sleepInMs > 0 && nowMs >= nightEndMs && nowMs - nightEndMs < sleepInMs && recentSteps60Min < sleepInSteps')
    return text


def preparation(reference=False):
    source = (ARCHIVE / 'OpenAPSBoostPlugin.kt.source').read_text()
    start = source.index('    data class BoostActivityResult(')
    end = source.index('    /**\n     * [parseTimeToMillis]', start)
    body = source[start:end]
    # Return the already merged step windows as well, so target profile assembly can
    # use the same snapshot rather than querying the database a second time.
    body = body.replace('val stepsFeed: String = "none"\n',
                        'val stepsFeed: String = "none",\n'
                        '        val steps5: Int = 0, val steps15: Int = 0,\n'
                        '        val steps30: Int = 0, val steps60: Int = 0\n')
    body = body.replace('stepsFeed = stepFeed.label\n',
                        'stepsFeed = stepFeed.label,\n'
                        '            steps5 = recentSteps5Min, steps15 = recentSteps15Min,\n'
                        '            steps30 = recentSteps30Min, steps60 = recentSteps60Min\n')
    body = body.replace('    private fun calculateBoostActivity(', '    suspend fun calculate(')
    body = body.replace('val midnight = now - MidnightUtils.milliSecFromMidnight(now)', 'val midnight = snapshot.dayStartMs')
    body = body.replace('parseTimeToMillis(boostStartTime)', 'settings.boostStartOfDayMs')
    body = body.replace('parseTimeToMillis(boostEndTime)', 'settings.boostEndOfDayMs')
    a = body.index('        val nowTime = java.time.Instant')
    b = body.index('\n\n', a)
    body = body[:a] + '        debug.append("Boost window: $boostStartTime–$boostEndTime | Now: ${snapshot.localTimeText}")' + body[b:]
    for minutes in (5, 15, 30, 60):
        body = body.replace(f'StepService.getRecentStepCount{minutes}Min()', f'snapshot.phoneSteps{minutes}')
    body = body.replace('StepService.feedState() == StepService.FeedState.LIVE', 'snapshot.phoneFeedLive')
    body = body.replace('dateUtil.now()', 'now')
    body = body.replace('sleepStateCached', 'snapshot.sleep')
    body = body.replace("snapshot.sleep is last\n        // cycle's state (this cycle's is evaluated later); a one-cycle (~5 min) lag is acceptable.",
                        "snapshot.sleep is the current\n        // cycle's state, evaluated before this activity preparation.")
    body = body.replace('lastSleepBoundaryExitMs', 'snapshot.lastSleepBoundaryExitMs')
    body = body.replace('hrLearnedDaytimeBpmCached', 'snapshot.learnedDaytimeHrBpm')
    body = body.replace('preferences.get(IntKey.ApsBoostSleepHysteresisMin)', 'settings.sleepHysteresisMin')
    body = body.replace('parseTimeToMillisOrDefault(preferences.get(StringKey.ApsBoostNightModeStart), "22:00")', 'settings.nightStartOfDayMs')
    body = body.replace('parseTimeToMillisOrDefault(preferences.get(StringKey.ApsBoostNightModeEnd), "07:00")', 'settings.nightEndOfDayMs')
    body = body.replace('String.format("%.0f", hrClassification.averageHrBpm)', 'formatHr(hrClassification.averageHrBpm)')
    body = body.replace('String.format("%.0f", hrClassification?.averageHrBpm ?: 0.0)', 'formatHr(hrClassification?.averageHrBpm ?: 0.0)')
    body = body.replace('aapsLogger.debug(', 'aapsLogger?.debug(')
    body = body.replace('Math.round(targetBg)', 'kotlin.math.floor(targetBg + 0.5).toLong()')
    body = body.replace('Math.round(dynIsfNormalTarget)', 'kotlin.math.floor(dynIsfNormalTarget + 0.5).toLong()')
    # The source catches Throwable for missing step rows. Keep coroutine cancellation meaningful.
    body = body.replace('catch (_: Throwable)', 'catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception)')
    names = ('sleepInHours', 'sleepInSteps', 'allowBoostWithHighTt',
             'dynIsfNormalTarget', 'inactivitySteps', 'inactivityPct', 'activitySteps5', 'activitySteps15',
             'activitySteps30', 'activitySteps60', 'activityPct', 'hrIntegrationEnabled', 'hrWindowMinutes',
             'hrMaxBpm', 'hrRestingBpm', 'hrStressDetection')
    properties = '\n'.join(f'    private val {name} get() = settings.{name}' for name in names)
    a = body.index('        var boostStart =')
    b = body.index('        // Disable boost if high temp target', a)
    body = body[:a] + """        val nightStartMs = midnight + settings.nightStartOfDayMs
        val nightEndMs = midnight + settings.nightEndOfDayMs
        val inNightWindow = NightWindow.contains(now, nightStartMs, nightEndMs)
        val rawNightPeriod = settings.nightModeEnabled && (inNightWindow ||
            (settings.autoBySleep && snapshot.sleep.state != SleepStateDetector.SleepState.AWAKE))
        val sleepSig = BoostEligibilityPolicy.sleepSignals(snapshot.sleep.state, rawNightPeriod,
            settings.nightModeEnabled, settings.autoBySleep, now, snapshot.lastSleepBoundaryExitMs,
            BoostEligibilityPolicy.boundaryExitHoldMin(settings.sleepHysteresisMin))
        val detectorAsleep = sleepSig.detectorAsleep
        var boostActive = BoostEligibilityPolicy.boostGateOpen(sleepSig.nightSleepPeriod,
            inNightWindow, v6Active = true, detectorSleeping = sleepSig.detectorSleeping)
        var disableReason = when {
            inNightWindow -> "Night window (clock)"
            sleepSig.nightSleepPeriod -> "Night/sleep period"
            !boostActive -> "Sleeping (V6 stands down; base oref SMB)"
            else -> ""
        }
        debug.append("Boost gate: window=$inNightWindow period=${sleepSig.nightSleepPeriod} sleeping=${sleepSig.detectorSleeping} | Now: ${snapshot.localTimeText}")
        if (sleepSig.boundaryHold) debug.append(" | sleep boundary-exit hold")

""" + body[b:]
    a = body.index('        // Sleep-in (lie-in) detection.')
    b = body.index('        // Audit trail for the 2026-07-31 fix:', a)
    body = body[:a] + """        // Current source anchors the lie-in to this cycle's learned/configured wake.
        val lieInEndOfNightMs = snapshot.effectiveNightEndMin?.let { midnight + it * 60_000L } ?: nightEndMs
        val sleepInActive = StepFeed.sleepInActive(stepsAvailable, now, lieInEndOfNightMs,
            sleepInMillis, recentSteps60Min, sleepInSteps)
""" + body[b:]
    a = body.index('        if (boostActive && sleepInActive) {')
    b = body.index('        var activityMinBg =', a)
    body = body[:a] + """        val genuineWakeThisMorning = snapshot.lastGenuineWakeMs >= lieInEndOfNightMs -
            SleepStateDetector.SLEEP_SCHEDULE_TOLERANCE_MIN * 60_000L
        if (boostActive && StepFeed.lieInFailsafeEngages(sleepInActive, settings.nightModeEnabled,
                settings.autoBySleep, sleepSig.detectorSleeping, genuineWakeThisMorning)) {
            boostActive = false
            disableReason = "Sleep-in failsafe (60m steps $recentSteps60Min < $sleepInSteps)"
        }

""" + body[b:]
    hold_start = source.index('internal fun sleepBoundaryHoldActive(')
    hold_end = source.index('/** Pure step-exercise evidence', hold_start)
    helpers = source[hold_start:hold_end] + source[source.index('internal fun boostExerciseStepsMet('):]
    helpers += '\n' + '\n'.join(source[source.index('internal fun boostNightGateOpen('):].splitlines()[:2]) + '\n'
    package = REFERENCE if reference else PACKAGE
    text = f'''package {package}

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSBoost.NightWindow
import app.aaps.plugins.aps.openAPSBoost.StepFeed
import app.aaps.plugins.aps.openAPSBoost.activity.BoostActivitySettings
import app.aaps.plugins.aps.openAPSBoost.activity.BoostActivitySnapshot
import kotlinx.coroutines.CancellationException

/** Complete source activity preparation; services and the captured cycle state are explicit. */
internal class BoostActivityPreparation(
    private val settings: BoostActivitySettings,
    private val snapshot: BoostActivitySnapshot,
    private val persistenceLayer: PersistenceLayer,
    private val aapsLogger: AAPSLogger? = null,
    private val formatHr: (Double?) -> String,
) {{
{properties}
{body}
}}

{helpers}'''
    if reference:
        text = text.replace('import app.aaps.plugins.aps.openAPSBoost.activity.BoostActivitySnapshot\n', '')
        text = text.replace('import app.aaps.plugins.aps.openAPSBoost.StepFeed\n', '')
    return text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write-port', action='store_true')
    parser.add_argument('--reference-output', type=Path)
    args = parser.parse_args()
    # Source hashes are independently pinned by verify_legacy_snapshot.py.
    for name in NAMES:
        expected = transform(name)
        path = PORT / (name + '.kt')
        if args.write_port:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(expected)
        elif path.read_text() != expected:
            raise ValueError('Activity source transformation diverges: ' + name)
        if args.reference_output:
            out = args.reference_output / Path(REFERENCE.replace('.', '/'))
            out.mkdir(parents=True, exist_ok=True)
            (out / (name + '.kt')).write_text(transform(name, reference=True))
    wake_test = ROOT / 'src/jvmTest/kotlin/app/aaps/plugins/boostport/activity/SleepLieInWakeTest.kt'
    if args.write_port:
        wake_test.write_text(sleep_wake_test())
    elif wake_test.read_text() != sleep_wake_test():
        raise ValueError('Upstream lie-in wake regressions diverge')
    if args.reference_output:
        text = (ARCHIVE / 'StepSourceResolver.kt.source').read_text().replace(
            'package app.aaps.plugins.aps.openAPSBoost', 'package ' + REFERENCE, 1)
        (out / 'StepSourceResolver.kt').write_text(text)
    prepared = PORT / 'BoostActivityPreparation.kt'
    if args.write_port:
        prepared.write_text(preparation())
    elif prepared.read_text() != preparation():
        raise ValueError('Activity preparation transformation diverges')
    if args.reference_output:
        (out / 'BoostActivityPreparation.kt').write_text(preparation(reference=True))
        (out / 'BoostActivityInputs.kt').write_text((PORT / 'BoostActivityInputs.kt').read_text().replace(
            'package ' + PACKAGE, 'package ' + REFERENCE, 1))
    print('Full source activity/sleep/learning and preparation transformations verified')

if __name__ == '__main__':
    main()
