#!/usr/bin/env python3
"""Check the portable V1 math against its archive; generate JVM-only reference sources.

Only namespace, data/presentation/time boundaries, primitive platform math and
caller-owned feature-history I/O change. The JVM reference retains Java math,
DecimalFormat, original JSON ring codec and original object-form safety helpers.
No generated reference code is included in an Android artifact.
"""
import argparse
import hashlib
import textwrap
import sys
sys.dont_write_bytecode = True
import activity_sources
import night_mode_sources
import sleep_telemetry_sources
import auto_config_sources
import diagnostic_sources
from pathlib import Path

MODULE = Path(__file__).resolve().parents[1]
ARCHIVE = MODULE / 'legacy-snapshot/plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoost'
ENGINE = MODULE / 'port-source/DetermineBasalBoost.kt.source'
PORT = MODULE / 'src/commonMain/kotlin/app/aaps/plugins/aps/openAPSBoost/BoostV1ReplayEngine.kt'
DATA_PORT = PORT.parent / 'replay'
PROFILE = MODULE / 'legacy-snapshot/core/interfaces/src/main/kotlin/app/aaps/core/interfaces/aps/OapsProfileBoost.kt.source'
REPLAY = 'app.aaps.plugins.aps.openAPSBoost.replay'
REFERENCE = 'app.aaps.plugins.boostport.reference'


def blob(path):
    data = path.read_bytes()
    return hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise ValueError('Archive boundary changed: ' + old[:100])
    return text.replace(old, new)


def boundaries(text, reference=False):
    text = text.replace('import app.aaps.core.data.configuration.Constants\n', '')
    text = text.replace('import app.aaps.core.interfaces.aps.APSResult\n', '')
    text = text.replace('import app.aaps.core.interfaces.profile.ProfileUtil\n', '')
    text = text.replace('import javax.inject.Inject\n', '').replace('import javax.inject.Singleton\n', '')
    text = text.replace('import app.aaps.core.interfaces.aps.OapsProfileBoost', f'import {REPLAY}.V1ReplayProfile as OapsProfileBoost')
    text = text.replace('import app.aaps.core.interfaces.aps.RT\n', f'import {REPLAY}.V1ReplayResult as RT\n')
    text = text.replace('APSResult.Algorithm.', 'V1ReplayAlgorithm.')
    text = text.replace('@Singleton\n', '')
    text = replace_once(text, 'class DetermineBasalBoost @Inject constructor(\n    private val profileUtil: ProfileUtil\n)',
                        ('internal class ReferenceDetermineBasalBoost' if reference else 'internal class BoostV1ReplayEngine') +
                        '(\n    private val presentation: V1ReplayPresentation,\n    private val replayClock: V1ReplayClock\n)')
    text = text.replace('profileUtil.fromMgdlToStringInUnits(value)', 'presentation.glucose(value)')
    text = text.replace('Instant.ofEpochMilli(currentTime).atZone(ZoneId.systemDefault()).toLocalDateTime().hour', 'replayClock.hourAt(currentTime)')
    text = text.replace('Instant.ofEpochMilli(rT.deliverAt!!).atZone(ZoneId.systemDefault()).toLocalDateTime().minute', 'replayClock.minuteAt(rT.deliverAt!!)')
    text = text.replace('java.time.LocalTime.now().hour', 'replayClock.hourAt(currentTime)')
    additions = f'''import {REPLAY}.V1ReplayAlgorithm
import {REPLAY}.V1ReplayClock
import {REPLAY}.V1ReplayPresentation
import {REPLAY}.V1ReplayRiskModel as BoostRiskModel
import {REPLAY}.V1ReplayMealModel as BoostMealModel
'''
    return text.replace('\n\nimport ', '\n\n' + additions + '\nimport ', 1)


def portable(source):
    s = boundaries(diagnostic_sources.guard_traces(source))
    s = s.replace('import java.text.DecimalFormat\n', '').replace('import java.time.Instant\n', '').replace('import java.time.ZoneId\n', '')
    s = s.replace('import kotlin.math.abs', f'import {REPLAY}.V1ReplayFeatures as BoostMlFeatureBuilder\nimport kotlin.math.floor\nimport kotlin.math.roundToLong\nimport kotlin.math.abs')
    a = s.index('    /**\n     * Restore the lookback ring buffer')
    b = s.index('    private fun Double.toFixed2()', a)
    s = s[:a] + '''    /** Restore source-shaped history once; persistence and parsing belong to the caller. */
    fun loadFeatureHistoryOnce(snapshots: List<BoostMlFeatureBuilder.CycleSnapshot>) {
        if (mlRingBufferLoaded) return
        mlRingBuffer = BoostMlFeatureBuilder.RingBuffer().also { buffer ->
            snapshots.forEach(buffer::push)
        }
        mlRingBufferLoaded = true
    }

    fun snapshotFeatureHistory(): List<BoostMlFeatureBuilder.CycleSnapshot> =
        mlRingBuffer.snapshots.toList()

''' + s[b:]
    s = replace_once(s, 'DecimalFormat("0.00#").format(round(this, 2))', 'presentation.fixed2(round(this, 2))')
    s = replace_once(s, 'DecimalFormat("0.##").format(this)', 'presentation.withoutZeros(this)')
    s = replace_once(s, 'Math.round(value * scale)', '(value * scale).roundToLong()')
    s = s.replace('Math.floorDiv(dayIndex, 7L)', 'dayIndex.floorDiv(7L)')
    s = s.replace('Math.floorMod(dayIndex, 7L)', 'dayIndex.mod(7L)')
    s = s.replace('Math.floorMod(block, 2L)', 'block.mod(2L)')
    for name in ('min', 'max', 'abs', 'floor'):
        s = s.replace('Math.' + name + '(', name + '(')
    return '// Full source V1 arithmetic, replay-only; platform boundaries are explicit.\n' + s


def reference(source):
    s = boundaries(diagnostic_sources.guard_traces(source), reference=True)
    return s.replace('package app.aaps.plugins.aps.openAPSBoost', 'package ' + REFERENCE, 1)


def portable_profile():
    text = PROFILE.read_text().replace('package app.aaps.core.interfaces.aps', 'package ' + REPLAY)
    text = text.replace('import kotlinx.serialization.Serializable\n', '').replace('@Serializable\n', '')
    text = text.replace('data class OapsProfileBoost(', 'internal data class V1ReplayProfile(')
    return '// Source profile schema; replay-only. Not registered as an AAPS profile.\n' + text


def portable_features():
    text = (ARCHIVE / 'BoostMlFeatureBuilder.kt.source').read_text()
    text = text.replace('package app.aaps.plugins.aps.openAPSBoost', 'package ' + REPLAY, 1)
    text = text.replace('import org.json.JSONArray\n', '').replace('import org.json.JSONObject\n', '')
    text = text.replace('object BoostMlFeatureBuilder {', 'internal object V1ReplayFeatures {')
    a = text.index('        fun toJson(): JSONObject')
    b = text.index('\n    }', a)
    text = text[:a] + text[b:]
    a = text.index('    fun serializeBuffer(')
    b = text.index('    /**', a)
    text = text[:a] + text[b:]
    text = text.replace(
        ' * is persisted across plugin restarts via [serializeBuffer]/[deserializeBuffer] into\n'
        ' * StringKey.ApsBoostMlRingBuffer (orchestrated by the plugin, not this object). On cold\n'
        ' * start (empty buffer), lag values default to\n',
        ' * is supplied/restored by the replay caller as typed snapshots. This object does\n'
        ' * not read JSON or application preferences. On cold start (empty buffer), lag values default to\n'
    )
    return '// Source feature ordering and lag arithmetic; persistence stays caller-owned.\n' + text


def reference_model(name):
    """Retain original JSON parsing, tree traversal, cache and projection math.

    Only Android asset reading, logging and injection are replaced. These models
    run ONLY in JVM tests and are independent of the portable parser/scorer.
    """
    text = (ARCHIVE / (name + '.kt.source')).read_text()
    text = text.replace('package app.aaps.plugins.aps.openAPSBoost', 'package ' + REFERENCE, 1)
    for dependency in ('android.content.Context', 'app.aaps.core.interfaces.logging.AAPSLogger',
                       'app.aaps.core.interfaces.logging.LTag', 'javax.inject.Inject', 'javax.inject.Singleton'):
        text = text.replace('import ' + dependency + '\n', '')
    interface = 'V1ReplayRiskModel' if name == 'BoostRiskModel' else 'V1ReplayMealModel'
    text = text.replace('import org.json.JSONObject', f'import {REPLAY}.{interface}\nimport org.json.JSONObject')
    old = ('@Singleton\nclass ' + name + ' @Inject constructor(\n'
           '    private val context: Context,\n    private val aapsLogger: AAPSLogger\n) {')
    text = replace_once(text, old,
                        f'internal class Reference{name}(private val readAsset: (String) -> String) : {interface} {{')
    text = text.replace('loadModel(context, defaultAssetPath)', 'loadModel(defaultAssetPath)')
    text = text.replace('fun loadModel(context: Context, assetPath:', 'fun loadModel(assetPath:')
    text = text.replace('context.assets.open(assetPath).readBytes()', 'readAsset(assetPath).toByteArray(Charsets.UTF_8)')
    text = text.replace('context.assets.open(assetPath).bufferedReader().readText()', 'readAsset(assetPath)')
    text = '\n'.join(line for line in text.split('\n') if 'aapsLogger.' not in line)
    methods = ('predictHypoRisk', 'predict', 'getFeatureNames') if name == 'BoostRiskModel' else ('predictMealLikelihood',)
    for method in methods:
        text = text.replace('    fun ' + method + '(', '    override fun ' + method + '(')
    return text


V5_PLUGIN = ARCHIVE.parent / 'openAPSBoostV5/OpenAPSBoostV5Plugin.kt.source'
V1_PLUGIN = ARCHIVE / 'OpenAPSBoostPlugin.kt.source'
TRANCHE = ARCHIVE.parent / 'openAPSBoostV5/ConfirmTrancheController.kt.source'
SOURCE_ROUND = MODULE / 'port-source/Round.kt.source'
V5_STATE = ARCHIVE.parent / 'openAPSBoostV5/V5StateStore.kt.source'
DYNAMIC_CR = MODULE.parents[1] / 'core/interfaces/port-source/DynamicCarbRatioCalculator.kt.source'
DYNAMIC_CR_PORT = MODULE.parents[1] / 'core/interfaces/src/commonMain/kotlin/app/aaps/core/interfaces/aps/DynamicCarbRatioCalculator.kt'


def dynamic_cr(reference=False):
    text = DYNAMIC_CR.read_text()
    text = text.replace('package app.aaps.plugins.aps.dynamiccr', 'package ' + (REFERENCE if reference else 'app.aaps.core.interfaces.aps'), 1)
    for dependency in ('javax.inject.Inject', 'javax.inject.Singleton'):
        text = text.replace('import ' + dependency + '\n', '')
    text = text.replace('@Singleton\nclass DynamicCarbRatioCalculator @Inject constructor()',
                        'internal class ReferenceDynamicCarbRatioCalculator' if reference else 'class DynamicCarbRatioCalculator')
    if reference: text = text.replace('import app.aaps.core.interfaces.utils.Round\n', '')
    return '// Source Dynamic CR math shared across APS algorithms.\n' + text


def reference_v5_store():
    text = V5_STATE.read_text().replace('package app.aaps.plugins.aps.openAPSBoostV5', 'package ' + REFERENCE, 1)
    for dependency in ('app.aaps.core.interfaces.logging.AAPSLogger', 'app.aaps.core.keys.StringKey',
                       'app.aaps.core.keys.interfaces.Preferences', 'app.aaps.plugins.aps.getBoostDosing'):
        text = text.replace('import ' + dependency + '\n', '')
    text = text.replace('import org.json.JSONObject', 'import app.aaps.plugins.aps.openAPSBoostV5.*\nimport org.json.JSONObject')
    text = replace_once(text,
        'class V5StateStore(private val preferences: Preferences, private val aapsLogger: AAPSLogger? = null)',
        'internal class ReferenceV5StateStore(private val readState: () -> String, private val writeState: (String) -> Unit)')
    text = text.replace('preferences.getBoostDosing(StringKey.ApsBoostV5State)', 'readState()')
    text = text.replace('preferences.put(StringKey.ApsBoostV5State, ', 'writeState(')
    text = '\n'.join(line for line in text.split('\n') if 'aapsLogger?.error(' not in line)
    return text


def v6_override(reference=False):
    """Extract the entire final dosing seam, excluding read-only shadow/log I/O.

    Settings, cycle inputs and the separate replay result replace app services.
    The V6 branch order, tranche state, fallback and temp-basal arithmetic stay
    source-shaped. Reference retains Java rounding and its own source controller.
    """
    source = V1_PLUGIN.read_text()
    a = source.index('            val cumulativeCapReached =')
    b = source.index('            // Post-meal PLATEAU-NUDGE shadow', a)
    body = source[a:b]
    body = '\n'.join(line for line in body.split('\n') if 'it.boostV5_' not in line)
    multi_log = '''                        aapsLogger.info(LTag.APS, "V6 primer (bolus): ${v5decision.primerBolusU}U folded into SMB" +
                            if (routeOverridden) " [user override of recommended temp-basal routing]" else "")'''
    body = replace_once(body, multi_log, '')
    for line in body.splitlines():
        if 'aapsLogger.' in line and not line.rstrip().endswith(')'):
            raise ValueError('Unreviewed multiline log in V6 seam')
    body = '\n'.join(line for line in body.split('\n') if 'aapsLogger.' not in line)
    replacements = {
        'BooleanKey.ApsBoostV5ConfirmTranche': 'settings.confirmTrancheEnabled',
        'DoubleKey.ApsBoostV5TrancheFraction': 'settings.immediateFraction',
        'DoubleKey.ApsBoostV5TrancheThreshold': 'settings.releaseThreshold',
        'DoubleKey.ApsBoostV5ConfirmedCapU': 'context.confirmedCapU',
        'BooleanKey.ApsBoostV5ComposedFloorActive': 'context.composedFloorRequested',
        'BooleanKey.ApsBoostV5VelocityBudgetActive': 'context.velocityBudgetRequested',
        'BooleanKey.ApsBoostV5PrimerTbrFallback': 'context.primerTbrFallback',
        'BooleanKey.ApsBoostV5PrimerBolusMode': 'context.primerBolusMode',
    }
    for key, value in replacements.items():
        body = body.replace('preferences.getBoostDosing(' + key + ')', value)
    if 'preferences.' in body or 'aapsLogger.' in body:
        raise ValueError('Unadapted V6 runtime service')
    body = body.replace('app.aaps.plugins.aps.openAPSBoostV5.ConfirmTrancheController.ReleaseBounds', 'ConfirmTrancheController.ReleaseBounds')
    a = source.index('            // A hold is invalid once the V6 override is skipped')
    b = source.index('            // V6: surface the anticipatory pre-meal target', a)
    body += source[a:b]
    # Helpers are extracted independently of their original plugin container.
    a = source.index('        internal data class V6OverrideCaps(')
    b = source.index('        /** Conservative night gate', a)
    caps = source[a:b]
    a = source.index('/** Remaining release headroom under both IOB and confirm-shot limits. */')
    b = source.index('/** Fail-closed gates for an anticipatory reduction', a)
    ceiling = source[a:b]
    a = source.index('/** Protective low temp wins; an existing high temp subsumes a V6 primer. */')
    b = source.index('/** Keep the nocturnal dosing gate closed', a)
    helpers = textwrap.dedent(caps) + ceiling + source[a:b]
    if not reference:
        helpers = helpers.replace('Math.round(currentBasal * minutes / 60.0 * 10.0)', '(currentBasal * minutes / 60.0 * 10.0).roundToLong()')
    body = textwrap.indent(textwrap.dedent(body), '        ')
    helpers = textwrap.indent(helpers, '    ')
    imports = (f'import {REPLAY}.*\n' if reference else
               'import app.aaps.core.interfaces.utils.Round\nimport app.aaps.plugins.aps.openAPSBoostV5.ConfirmTrancheController\nimport kotlin.math.roundToLong\n')
    return f'''// Source V6 final override, replay-only; no live APS/pump binding.
package {REFERENCE if reference else REPLAY}

{imports}import app.aaps.plugins.aps.openAPSBoostV5.V5Decision
import app.aaps.plugins.aps.openAPSBoostV5.MealHypothesis
import kotlin.math.max

/** One sequential replay session; tranche holds are in memory, as in the source plugin. */
internal class {'ReferenceV6ReplayOverride' if reference else 'V6ReplayOverride'} {{
    private val confirmTranche = ConfirmTrancheController()

    fun heldTrancheU(): Double = confirmTranche.heldU()

    /** Return a separate candidate; never mutate the V1 result or issue a command. */
    fun apply(
        base: V1ReplayResult,
        v5decision: V5Decision?,
        cycle: V1ReplayCycle,
        context: V5ReplayContext,
        settings: V6ReplaySettings,
    ): V1ReplayResult {{
        val it = base.copy(reason = StringBuilder(base.reason.toString()))
        val v5Active = context.activeMode
        val microBolusAllowed = cycle.microBolusAllowed
        val v5Asleep = context.asleep
        val inPostRescueWindow = cycle.recentLowBG45Min < 75.0
        val cumulativeSmbCap60Min = cycle.cumulativeSmbCap60Min
        val recentSmbVolume60Min = cycle.recentSmbVolume60Min
        val recentLowBG45Min = cycle.recentLowBG45Min
        val oapsProfile = cycle.profile
        val iobArray = cycle.iob
        val glucoseStatus = cycle.glucose
        val currentTemp = cycle.currentTemp
        val now = cycle.time
''' + body + '''
        return it
    }

''' + helpers + '\n}\n'


def isf_preparation(reference=False):
    """Full upstream source ISF arithmetic, with TDD/settings supplied explicitly.

    EMA/VWA shadows, their history reads and log I/O are diagnostic only and are
    omitted. No source ISF/TDD/TT/autosens branch is changed by that omission.
    """
    source = V1_PLUGIN.read_text()
    a = source.index('    private fun calculateBoostIsf(')
    b = source.index('    // ---- Boost Time Window & Activity Detection', a)
    body = source[a:b]
    a = body.index('        val autosensMax =')
    body = body[a:]
    body = body.replace('    ): BoostIsfResult {', '    ): BoostIsfPreparationResult {')
    body = body.replace('BoostIsfResult(', 'BoostIsfPreparationResult(')
    body = body.replace('var sensNormalTarget = profileSens', 'var shadowEligible = false\n        var sensNormalTarget = profileSens')
    body = body.replace('sensNormalTarget = 1800.0 / (tdd * logTerm)', 'shadowEligible = true\n                    sensNormalTarget = 1800.0 / (tdd * logTerm)')
    body = body.replace('isfDebug = debug.toString(),', 'isfDebug = debug.toString(),\n            shadowEligible = shadowEligible,')
    a = body.index('                    // ISF shadow — compute')
    b = body.index('                } else {', a)
    body = body[:a] + body[b:]
    body = '\n'.join(line for line in body.split('\n') if not any(token in line for token in
        ('var isfShadowResult:', 'var vwaShadowResult:', 'tddSensShadow = ', 'vwaTddShadow = ', 'aapsLogger.')))
    replacements = {
        'preferences.get(DoubleKey.AutosensMax)': 'settings.autosensMax',
        'preferences.get(DoubleKey.AutosensMin)': 'settings.autosensMin',
        'preferences.getBoostDosing(BooleanKey.ApsBoostUseTdd)': 'settings.useTdd',
        'preferences.get(BooleanKey.ApsAutoIsfHighTtRaisesSens)': 'settings.highTtRaisesSens',
        'preferences.get(BooleanKey.ApsAutoIsfLowTtLowersSens)': 'settings.lowTtLowersSens',
        'preferences.getBoostDosing(BooleanKey.ApsBoostAdjustSensitivity)': 'settings.adjustSensitivity',
        'preferences.get(IntKey.ApsBoostDynIsfAdjustmentFactor)': 'settings.adjustmentFactor',
        'preferences.get(BooleanKey.ApsBoostAutosensWhenNoTdd)': 'settings.autosensWhenNoTdd',
        'dynIsfVelocity': 'settings.velocity',
        'dynIsfBgCap': 'settings.bgCap',
        'dynIsfNormalTarget': 'settings.normalTarget',
        'SMBDefaults.half_basal_exercise_target': 'settings.halfBasalTarget',
        'tddCalculator.averageTDD(tddCalculator.calculate(7, allowMissingDays = true))?.data?.totalAmount': 'parts.average7Days',
        'tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = true))?.data?.totalAmount': 'parts.average1Day',
        'tddCalculator.calculateDaily(-24, 0)?.totalAmount': 'parts.last24Hours',
        'tddCalculator.calculateDaily(-4, 0)?.totalAmount': 'parts.last4Hours',
        'tddCalculator.calculateDaily(-8, -4)?.totalAmount': 'parts.last8To4Hours',
    }
    for old, new in replacements.items():
        body = body.replace(old, new)
    # Do not replace the RESULT field name dynIsfVelocity by a settings expression.
    body = body.replace('settings.velocity = velocity,', 'dynIsfVelocity = velocity,')
    if any(token in body for token in ('preferences.', 'tddCalculator.', 'aapsLogger.', 'System.')):
        raise ValueError('Unadapted source ISF runtime boundary')
    a = source.index('        internal fun autosensAdjustedIsf(')
    b = source.index('        /** Only cancel a recovery target', a)
    helpers = textwrap.dedent(source[a:b])
    a = source.index('        internal fun selectSensitivityRatio(')
    b = source.index('        /** Outcome of the V6-override dose caps', a)
    helpers += textwrap.dedent(source[a:b])
    imports = (f'import {REPLAY}.*\n' if reference else 'import app.aaps.core.interfaces.utils.Round\n')
    return f'''// Complete source ISF preparation; explicit inputs, no app-service mutation.
package {REFERENCE if reference else REPLAY}

{imports}import kotlin.math.ln
import kotlin.math.min
import kotlin.math.max

internal object {'ReferenceBoostIsfPreparation' if reference else 'BoostIsfPreparation'} {{
    fun calculate(input: BoostIsfInputs, settings: BoostIsfSettings, parts: BoostTddParts): BoostIsfPreparationResult {{
        val profileSens = input.profileSens
        val profilePercent = input.profilePercent
        val targetBg = input.targetBg
        val insulinDivisor = input.insulinDivisor
        val glucoseValue = input.glucoseValue
        val isTempTarget = input.isTempTarget
        val orefAutosensRatio = input.orefAutosensRatio
''' + body + '\n' + textwrap.indent(helpers, '    ') + '}\n'


def reference_profile_adapter():
    source = V1_PLUGIN.read_text()
    a = source.index('        val oapsProfile = OapsProfileBoost(')
    b = source.index('        aapsLogger.debug(LTag.APS, ">>> Invoking determine_basal Boost <<<")', a)
    body = source[a:b].replace('val oapsProfile = OapsProfileBoost(', 'return V1ReplayProfile(', 1)
    body = '\n'.join(line for line in body.split('\n') if 'aapsLogger.' not in line)
    mapping = {
        'constraintsChecker.getMaxIOBAllowed().also { inputConstraints.copyReasons(it) }.value()': 'base.max_iob',
        'constraintsChecker.getMaxBasalAllowed(profile).also { inputConstraints.copyReasons(it) }.value()': 'base.max_basal',
        'profile.getMaxDailyBasal()': 'base.max_daily_basal', 'profile.getIc()': 'base.carb_ratio',
        'profile.getIsfMgdl("OpenAPSBoostPlugin")': 'base.sens',
        'profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsLgsThreshold)).toInt()': 'base.lgsThreshold',
        'constraintsChecker.isUAMEnabled().also { inputConstraints.copyReasons(it) }.value()': 'base.enableUAM',
        'pump.setNeutralTempAtFullHour()': 'base.skip_neutral_temps',
        'pump.pumpDescription.bolusStep': 'base.bolus_increment',
        'if (profileFunction.getUnits() == GlucoseUnit.MMOL) "mmol/L" else "mg/dl"': 'base.out_units',
    }
    key_fields = {
        'DoubleKey.ApsMaxDailyMultiplier': 'max_daily_safety_multiplier',
        'DoubleKey.ApsMaxCurrentBasalMultiplier': 'current_basal_safety_multiplier',
        'DoubleKey.AutosensMax': 'autosens_max', 'IntKey.ApsMaxSmbFrequency': 'SMBInterval',
        'IntKey.ApsMaxMinutesOfBasalToLimitSmb': 'maxSMBBasalMinutes',
        'IntKey.ApsUamMaxMinutesOfBasalToLimitSmb': 'maxUAMSMBBasalMinutes',
        'IntKey.ApsCarbsRequestThreshold': 'carbsReqThreshold',
    }
    for key, field in key_fields.items(): mapping['preferences.get(' + key + ')'] = 'base.' + field
    for key, field in (
        ('ApsUseSmbWithCob', 'enableSMB_with_COB'), ('ApsUseSmbWithLowTt', 'enableSMB_with_temptarget'),
        ('ApsUseSmbWithHighTt', 'allowSMB_with_high_temptarget'),
        ('ApsUseSmbAlways', 'enableSMB_always'), ('ApsUseSmbAfterCarbs', 'enableSMB_after_carbs')):
        expr = 'smbEnabled && preferences.get(BooleanKey.' + key + ')'
        if key in ('ApsUseSmbAlways', 'ApsUseSmbAfterCarbs'): expr += ' && advancedFiltering'
        mapping[expr] = 'base.' + field
    mapping.update({
        'preferences.get(BooleanKey.ApsAutoIsfHighTtRaisesSens)': 'settings.highTtRaisesSens',
        'preferences.get(BooleanKey.ApsAutoIsfLowTtLowersSens)': 'settings.lowTtLowersSens',
    })
    for old,new in mapping.items(): body = body.replace(old,new)
    for field in ('exercise_mode', 'half_basal_exercise_target', 'maxCOB', 'remainingCarbsCap', 'A52_risk_enable'):
        body = body.replace('SMBDefaults.' + field, 'base.' + field)
    for field in ('boostActive', 'profileSwitch', 'activityState', 'debugReason'):
        name = {'profileSwitch':'profilePercent', 'activityState':'activityState', 'debugReason':'boostDebug'}.get(field,field)
        body = body.replace('activityResult.' + field, 'context.' + name)
    locals_map = {
        'useTdd':'settings.useTdd', 'v6MinBg':'context.minBg', 'v6MaxBg':'context.maxBg', 'v6TargetBg':'context.targetBg',
        'isTempTarget':'base.temptargetSet', 'dynIsfBgCap':'settings.bgCap', 'dynIsfNormalTarget':'settings.normalTarget',
        'postExerciseRecoveryEnabled':'context.recoveryEnabled', 'recoveryWindowEnd':'context.recoveryEndMs',
        'boostBolus':'context.boostBolus', 'activeRecoveryScale':'context.recoveryScale',
        'boostMaxIob':'context.boostMaxIob', 'boostInsulinReqPct':'context.insulinReqPercent',
        'boostScale':'context.boostScale', 'boostPercentScale':'context.percentScale',
        'enableBoostPercentScale':'context.enablePercentScale', 'enableCircadianIsf':'context.circadianIsf',
        'allowBoostWithHighTt':'context.allowBoostWithHighTarget', 'recentSteps5Min':'context.steps5',
        'recentSteps15Min':'context.steps15', 'recentSteps30Min':'context.steps30', 'recentSteps60Min':'context.steps60',
        'recentLowBG':'context.recentLowBg', 'recentBrakingProduct':'context.brakingProduct',
        'dynamicCarbRatio':'context.dynamicCarbRatio', 'dynamicCarbRatioReason':'context.dynamicCarbRatioReason',
    }
    # Replace only expressions, not the source constructor's left-hand field names/comments.
    import re
    for name,new in locals_map.items():
        body = re.sub(r'(?<![.\w])' + re.escape(name) + r'\b(?!\s*=)', new, body)
    if any(token in body for token in ('preferences.', 'constraintsChecker.', 'activityResult.', 'pump.', 'profileFunction.')):
        raise ValueError('Unadapted profile constructor boundary')
    a = source.index('internal val V5_EXERCISE_STATES =')
    b = source.index('/** Remaining release headroom', a)
    helpers = source[a:b]
    return f'''package {REFERENCE}

import {REPLAY}.*
import app.aaps.core.interfaces.aps.OapsProfile

internal object ReferenceBoostProfileAdapter {{
    fun build(base: OapsProfile, settings: BoostIsfSettings, context: BoostProfileContext,
              isfResult: BoostIsfPreparationResult): V1ReplayProfile {{
        val insulinPeak = context.insulinPeak.coerceIn(30, 75)
        val insulinDivisor = if (insulinPeak < 60) (90 - insulinPeak) + 30 else (90 - insulinPeak) + 40
        val now = context.atMs
        val profileScale = context.profilePercent.toDouble() / 100.0
        val currentBasal = if (context.profilePercent != 100) base.current_basal * profileScale else base.current_basal
''' + body + '\n    }\n' + textwrap.indent(helpers, '    ') + '}\n'


def reference_v5_inputs():
    """Original plugin input assembly, with external context explicitly recorded."""
    text = V5_PLUGIN.read_text()
    a = text.index('    private fun shortHorizonMinGuard(')
    b = text.index('    /** Delegates', a)
    guard = text[a:b]
    a = text.index('    private fun buildInputs(')
    b = text.index('    /**\n     * 2026-10-05:', a)
    body = text[a:b].replace('    private fun buildInputs(', '''    fun referenceV5Inputs(
        context: V5ReplayContext,
        clock: V1ReplayClock,
        recordedTime: Long,
        projectedRisk: (Double) -> Double?,''', 1)
    body = body.replace('java.time.Instant.ofEpochMilli(dateUtil.now()).atZone(ZoneId.systemDefault()).hour', 'clock.hourAt(recordedTime)')
    body = body.replace('boostRiskModel.predictAtProjectedIob(projIob)', 'projectedRisk(projIob)')
    keys = {
        'BooleanKey.ApsBoostV5ComposedFloorActive': 'composedFloorRequested',
        'BooleanKey.ApsBoostV5VelocityBudgetActive': 'velocityBudgetRequested',
        'BooleanKey.ApsBoostV5FastCarbConfirm': 'fastCarbConfirmEnabled',
        'BooleanKey.ApsBoostV5AggressiveEarlyConfirm': 'aggressiveEarlyConfirmEnabled',
        'DoubleKey.ApsBoostV5ConfirmedCapU': 'confirmedCapU',
        'DoubleKey.ApsBoostV5CommittedCapU': 'committedCapU',
        'DoubleKey.ApsBoostV5PrimerCapU': 'primerCapU',
        'BooleanKey.ApsBoostV5PrimerTbrFallback': 'primerTbrFallback',
        'BooleanKey.ApsBoostV5PrimerBolusMode': 'primerBolusMode',
    }
    for key, field in keys.items():
        body = body.replace('preferences.getBoostDosing(' + key + ')', 'context.' + field)
    body = body.replace('composedFloorTbrAllowed(dateUtil.now())', 'context.insulinAddingFloorsAllowed')
    body = body.replace('profileSwitchedSinceLastCycle(dateUtil.now())', 'context.profileSwitched')
    body = body.replace('runningModeAt(dateUtil.now())?.isPumpSuspended() == true', 'context.pumpDisconnected')
    body = body.replace('runningModeAt(dateUtil.now())?.let { it.isSuspended() && !it.isPumpSuspended() } == true', 'context.loopSuspended')
    body = body.replace('mealAnnounced(rT, dateUtil.now())', '((rT.COB ?: 0.0) > 0.0 || context.manualBolusInAnnouncementWindow)')
    for name, field in (('aggressionKnob', 'aggression'), ('hypoCautionKnob', 'hypoCaution'), ('sensitivityKnob', 'sensitivity')):
        body = body.replace(name, 'context.' + field)
    body = body.replace('dateUtil.now()', 'recordedTime')
    return f'''package {REFERENCE}

import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import {REPLAY}.V1ReplayProfile as OapsProfileBoost
import {REPLAY}.V1ReplayResult as RT
import {REPLAY}.V1ReplayClock
import {REPLAY}.V5ReplayContext
import app.aaps.plugins.aps.openAPSBoostV5.V5Inputs
import kotlin.math.abs
import kotlin.math.max

internal object ReferenceV5InputAssembly {{
''' + guard + body + '}\n'


def verify_archive():
    expected = {
        ENGINE: '65b56ebb9a9db6d5b5cc3a8c020f152dfc2fd0a1',
        ARCHIVE / 'BoostMlFeatureBuilder.kt.source': 'ca8991eda752393ddf9739780a801ebf4166814f',
        ARCHIVE / 'BoostV1SafetyRules.kt.source': '1a6ee787120bdd1e8a209a0e325dfe1b859cd18b',
        PROFILE: '0a9c30f87944bdc2dae4d66759e3247a4e56b58a',
        ARCHIVE / 'BoostRiskModel.kt.source': '4775125e345eb41e2b613fb38142540d69c272fa',
        ARCHIVE / 'BoostMealModel.kt.source': '3cb022777238019a2f6ae52c33ff1236a9d5fd05',
        V5_PLUGIN: '082573a05735f83323a78cae757c5661cd430918',
        V1_PLUGIN: 'e33fba201719c3400b68b9c27a3453a2d85537ab',
        TRANCHE: '1baa8290ce84cac4e749fd0c1665d4f88ed21d9e',
        SOURCE_ROUND: 'dbf9863fd39c61a636e2c446eb9304828c209d79',
        V5_STATE: '26fa560c847a6bc37d4055deb9236f1c8bc6cfdb',
        DYNAMIC_CR: 'd52220b5e148ccd276915cde90d4b473e332324e',
    }
    for path, sha in expected.items():
        if blob(path) != sha:
            raise ValueError('Unreviewed source archive: ' + str(path))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write-port', action='store_true', help='explicit maintainer action, never run during setup/build')
    parser.add_argument('--reference-output', type=Path)
    args = parser.parse_args()
    verify_archive()
    s = ENGINE.read_text()
    ports = {
        diagnostic_sources.PORT: diagnostic_sources.vwa(),
        PORT: portable(s),
        DATA_PORT / 'V1ReplayProfile.kt': portable_profile(),
        DATA_PORT / 'V1ReplayFeatures.kt': portable_features(),
        DATA_PORT / 'V6ReplayOverride.kt': v6_override(),
        DATA_PORT / 'BoostIsfPreparation.kt': isf_preparation(),
        DYNAMIC_CR_PORT: dynamic_cr(),
    }
    ports.update({activity_sources.PORT / (name + '.kt'): activity_sources.transform(name)
                  for name in activity_sources.NAMES})
    ports[activity_sources.PORT / 'BoostActivityPreparation.kt'] = activity_sources.preparation()
    ports[activity_sources.PORT / 'BoostEligibilityPolicy.kt'] = activity_sources.eligibility()
    ports[activity_sources.PORT.parent / 'StepFeed.kt'] = activity_sources.step_feed()
    for path, expected in ports.items():
        if args.write_port:
            path.write_text(expected)
        elif path.read_text() != expected:
            raise ValueError('Portable V1 diverges from the reviewed transformation: ' + path.name)
    if args.reference_output:
        out = args.reference_output / Path(REFERENCE.replace('.', '/'))
        out.mkdir(parents=True, exist_ok=True)
        (out / 'ReferenceDetermineBasalBoost.kt').write_text(reference(s))
        for name in ('BoostMlFeatureBuilder', 'BoostV1SafetyRules'):
            text = (ARCHIVE / (name + '.kt.source')).read_text()
            text = text.replace('package app.aaps.plugins.aps.openAPSBoost', 'package ' + REFERENCE, 1)
            text = text.replace('import app.aaps.core.interfaces.aps.OapsProfileBoost', f'import {REPLAY}.V1ReplayProfile as OapsProfileBoost')
            (out / (name + '.kt')).write_text(text)
        for name in ('BoostRiskModel', 'BoostMealModel'):
            (out / ('Reference' + name + '.kt')).write_text(reference_model(name))
        (out / 'ReferenceV5InputAssembly.kt').write_text(reference_v5_inputs())
        (out / 'ReferenceV6ReplayOverride.kt').write_text(v6_override(reference=True))
        (out / 'ConfirmTrancheController.kt').write_text(TRANCHE.read_text().replace(
            'package app.aaps.plugins.aps.openAPSBoostV5', 'package ' + REFERENCE, 1))
        (out / 'Round.kt').write_text(SOURCE_ROUND.read_text().replace(
            'package app.aaps.core.interfaces.utils', 'package ' + REFERENCE, 1))
        (out / 'ReferenceV5StateStore.kt').write_text(reference_v5_store())
        (out / 'ReferenceBoostIsfPreparation.kt').write_text(isf_preparation(reference=True))
        (out / 'ReferenceBoostProfileAdapter.kt').write_text(reference_profile_adapter())
        (out / 'ReferenceDynamicCarbRatioCalculator.kt').write_text(dynamic_cr(reference=True))
        activity_out = args.reference_output / Path(activity_sources.REFERENCE.replace('.', '/'))
        activity_out.mkdir(parents=True, exist_ok=True)
        for name in activity_sources.NAMES:
            (activity_out / (name + '.kt')).write_text(activity_sources.transform(name, reference=True))
        (activity_out / 'StepSourceResolver.kt').write_text((ARCHIVE / 'StepSourceResolver.kt.source').read_text().replace(
            'package app.aaps.plugins.aps.openAPSBoost', 'package ' + activity_sources.REFERENCE, 1))
        (activity_out / 'BoostActivityInputs.kt').write_text((activity_sources.PORT / 'BoostActivityInputs.kt').read_text().replace(
            'package ' + activity_sources.PACKAGE, 'package ' + activity_sources.REFERENCE, 1))
        (activity_out / 'BoostActivityPreparation.kt').write_text(activity_sources.preparation(reference=True))
        (activity_out / 'BoostEligibilityPolicy.kt').write_text(activity_sources.eligibility(reference=True))
        (activity_out / 'StepFeed.kt').write_text(activity_sources.step_feed(reference=True))
        night_mode_sources.write_reference(args.reference_output)
        sleep_telemetry_sources.write_reference(args.reference_output)
        auto_config_sources.write_reference(args.reference_output)
        diagnostic_sources.write_reference(args.reference_output)
    print('V1/V5/V6 archives and reviewed portable arithmetic transformations verified')


if __name__ == '__main__':
    main()
