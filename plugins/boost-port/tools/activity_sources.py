#!/usr/bin/env python3
"""Mechanically port the source activity/sleep/learning arithmetic and retain a JVM oracle."""
import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ARCHIVE = ROOT / 'legacy-snapshot/plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost'
PORT = ROOT / 'src/commonMain/kotlin/app/aaps/plugins/aps/openAPSBoost/activity'
PACKAGE = 'app.aaps.plugins.aps.openAPSBoost.activity'
REFERENCE = 'app.aaps.plugins.boostport.reference.activity'
NAMES = ('HrActivityCalculator', 'SleepStateDetector', 'SleepHistoryTracker', 'DailyStepHistoryTracker', 'MealTimeLearner', 'WearStepSource')

def transform(name, reference=False):
    text = (ARCHIVE / (name + '.kt.source')).read_text()
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
    names = ('boostStartTime', 'boostEndTime', 'sleepInHours', 'sleepInSteps', 'allowBoostWithHighTt',
             'dynIsfNormalTarget', 'inactivitySteps', 'inactivityPct', 'activitySteps5', 'activitySteps15',
             'activitySteps30', 'activitySteps60', 'activityPct', 'hrIntegrationEnabled', 'hrWindowMinutes',
             'hrMaxBpm', 'hrRestingBpm', 'hrStressDetection')
    properties = '\n'.join(f'    private val {name} get() = settings.{name}' for name in names)
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

/** Complete source activity preparation; services and previous-cycle state are explicit. */
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
