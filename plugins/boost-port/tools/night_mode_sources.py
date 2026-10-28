#!/usr/bin/env python3
"""Generate a JVM oracle from the archived night policy, retaining its decision body."""
from pathlib import Path

MODULE = Path(__file__).resolve().parents[1]
ARCHIVE = MODULE / 'port-source/6.2.0'
FROZEN = MODULE / 'legacy-snapshot/plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost'
PACKAGE = 'app.aaps.plugins.boostport.reference.night'


def method(signature, end_marker):
    source = (ARCHIVE / 'OpenAPSBoostPlugin.kt.source').read_text()
    start = source.index(signature)
    return source[start:source.index(end_marker, start)].rstrip()


def write_reference(output):
    body = method('    private fun isNightModeActiveImpl(): Boolean {', '\n    override fun isUAMEnabled')
    # Bind the original platform/service reads to one explicit, canonical snapshot.
    # The decisions, ordering, short-circuits and hard-limit call remain source code.
    body = body.replace('private fun isNightModeActiveImpl()', 'fun isActive()')
    body = body.replace('getBoostDosing(BooleanKey.', 'get(BooleanKey.')
    period = method('    private fun isInNightSleepPeriod(): Boolean {', '\n    private fun isNightModeActive(): Boolean {')
    period = period.replace('System.currentTimeMillis()', 'context.nowMs').replace('MidnightUtils.milliSecFromMidnight(now)', '(now - context.dayStartMs)').replace('getBoostDosing(', 'get(')
    body = body.replace('System.currentTimeMillis()', 'context.nowMs')
    body = body.replace('MidnightUtils.milliSecFromMidnight(now)', '(now - context.dayStartMs)')
    body = body.replace('app.aaps.core.ui.R.string.temp_target_value', '0')
    clock = method('    private fun parseTimeToMillis(timeStr: String): Long {',
                   '\n    /** Returns minute-of-day')
    clock = clock.replace('private fun parseTimeToMillis', 'fun parseTimeToMillis')
    prefix = '''package PACKAGE

import app.aaps.plugins.boostport.aps.BoostNightModePolicy
import app.aaps.plugins.aps.openAPSBoost.activity.SleepStateDetector
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

internal class ReferenceBoostNightMode(
    private val settings: BoostNightModePolicy.Settings,
    private val context: BoostNightModePolicy.Context,
) {
    private enum class BooleanKey { ApsBoostNightModeEnabled, ApsBoostNightModeAutoBySleep,
        ApsBoostNightModeDisableWithCob, ApsBoostNightModeDisableWithLowTt }
    private enum class StringKey { ApsBoostNightModeStart, ApsBoostNightModeEnd }
    private enum class UnitDoubleKey { ApsBoostNightModeBgOffset }
    private object LTag { val APS = Unit }
    private object HardLimits { val LIMIT_TEMP_TARGET_BG = doubleArrayOf(72.0, 200.0) }
    private val aapsLogger = object { fun error(tag: Any, message: String) {} }
    private val hardLimits = object {
        fun verifyHardLimits(value: Double, resource: Int, low: Double, high: Double) = value.coerceIn(low, high)
    }
    private val profileUtil = object {
        val units = "mg/dl"
        fun convertToMgdl(value: Double, units: String) = value
    }
    private val preferences = object {
        fun get(key: BooleanKey): Boolean = when (key) {
            BooleanKey.ApsBoostNightModeEnabled -> settings.enabled
            BooleanKey.ApsBoostNightModeAutoBySleep -> settings.autoBySleep
            BooleanKey.ApsBoostNightModeDisableWithCob -> settings.disableWithCob
            BooleanKey.ApsBoostNightModeDisableWithLowTt -> settings.disableWithLowTempTarget
        }
        fun get(key: StringKey): String {
            val ms = if (key == StringKey.ApsBoostNightModeStart) settings.startOfDayMs else settings.endOfDayMs
            return "${ms / 3600000L}:${((ms / 60000L) % 60).toString().padStart(2, '0')}"
        }
        fun getBoostDosing(key: UnitDoubleKey, profile: Any) = settings.bgOffsetMgDl
    }
    private data class Glucose(val glucose: Double)
    private data class Profile(val value: Double) { fun getTargetMgdl() = value }
    private data class Target(val value: Double) { fun target() = value }
    private data class Meal(val mealCOB: Double)
    private val glucoseStatusCalculatorSMB = object {
        fun getGlucoseStatusData(allowOldData: Boolean) = context.glucoseMgDl?.let(::Glucose)
    }
    private val profileFunction = object {
        fun getProfile() = context.profileTargetMgDl?.let(::Profile)
    }
    private val iobCobCalculator = object {
        fun getMealDataWithWaitingForCalculationFinish() = Meal(context.mealCob)
    }
    private val persistenceLayer = object {
        fun getTemporaryTargetActiveAt(now: Long) = context.temporaryTargetMgDl?.let(::Target)
    }
    private val dateUtil = object { fun now() = context.nowMs }
    private val sleepStateCached = SleepStateDetector.State(state = context.sleepState)
    private val sleepInActiveCached = context.lieInActive

'''.replace('PACKAGE', PACKAGE)
    out = output / Path(PACKAGE.replace('.', '/'))
    out.mkdir(parents=True, exist_ok=True)
    (out / 'ReferenceBoostNightMode.kt').write_text(prefix + period + '\n\n' + body + '\n\n' + clock + '\n}\n')
    window = (FROZEN / 'NightWindow.kt.source').read_text()
    (out / 'NightWindow.kt').write_text(window.replace('package app.aaps.plugins.aps.openAPSBoost', 'package ' + PACKAGE, 1))


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference-output', type=Path, required=True)
    write_reference(parser.parse_args().reference_output)
