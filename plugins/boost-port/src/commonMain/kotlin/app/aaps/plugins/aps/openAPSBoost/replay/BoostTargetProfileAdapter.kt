package app.aaps.plugins.aps.openAPSBoost.replay

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.OapsProfile
import app.aaps.core.interfaces.aps.DynamicCarbRatioPolicy

/** Source activity/recovery/meal context. The caller has already resolved time windows. */
internal data class BoostProfileContext(
    val atMs: Long, val glucose: Double, val profilePercent: Int,
    val isfTargetBg: Double, val minBg: Double, val maxBg: Double, val targetBg: Double,
    val insulinPeak: Int, val boostActive: Boolean, val boostBolus: Double,
    val boostMaxIob: Double, val insulinReqPercent: Double, val boostScale: Double,
    val percentScale: Double, val enablePercentScale: Boolean, val circadianIsf: Boolean,
    val allowBoostWithHighTarget: Boolean, val activityState: String,
    val recoveryEnabled: Boolean = false, val recoveryEndMs: Long = 0,
    val recoveryScale: Double = 1.0,
    val steps5: Int = 0, val steps15: Int = 0, val steps30: Int = 0, val steps60: Int = 0,
    val recentLowBg: Double = 999.0, val brakingProduct: Double = 0.0,
    val boostDebug: String = "", val dynamicCarbRatio: Double = 0.0,
    val dynamicCarbRatioReason: String = "",
    val carbRatioTargetBg: Double = isfTargetBg,
    val temporaryTargetOverride: Boolean? = null,
)

/**
 * Target OapsProfile -> source-shaped full Boost profile. Basal and insulin values
 * must already be in the target's canonical units with hard constraints applied.
 * Never reads pump/profile services, reapplies a concentration conversion, or
 * recalculates the caller's oref Autosens. All 73 profile fields are populated.
 */
internal object BoostTargetProfileAdapter {
    data class Prepared(val profile: V1ReplayProfile, val autosens: AutosensResult, val isf: BoostIsfPreparationResult)

    /** Use the same application policy/settings as SMB, AutoISF and AMA. Reuse the captured TDD. */
    suspend fun prepareWithSharedCr(base: OapsProfile, autosens: AutosensResult, settings: BoostIsfSettings,
                                  parts: BoostTddParts, context: BoostProfileContext,
                                  dynamicCr: DynamicCarbRatioPolicy, profileCarbRatio: Double): Prepared {
        // A reported target OapsProfile may already carry its algorithm's dynamic CR.
        // Require the original profile CR explicitly so the common policy runs only once.
        val original = base.copy(carb_ratio = profileCarbRatio)
        val result = dynamicCr.calculate(DynamicCarbRatioPolicy.Input(profileCarbRatio, context.glucose,
            context.carbRatioTargetBg, base.sens, context.atMs),
            DynamicCarbRatioPolicy.TddParts(parts.average7Days, parts.average1Day, parts.last24Hours,
                parts.last4Hours, parts.last8To4Hours))
        return prepare(original, autosens, settings, parts, context.copy(
            dynamicCarbRatio = if (result.applied) result.carbRatio else 0.0,
            dynamicCarbRatioReason = result.reason))
    }

    fun prepare(base: OapsProfile, autosens: AutosensResult, settings: BoostIsfSettings,
                parts: BoostTddParts, context: BoostProfileContext): Prepared {
        val scale = context.profilePercent.toDouble() / 100.0
        val peak = context.insulinPeak.coerceIn(30, 75)
        val divisor = if (peak < 60) (90 - peak) + 30 else (90 - peak) + 40
        val isf = BoostIsfPreparation.calculate(BoostIsfInputs(base.sens / scale, context.profilePercent,
            context.isfTargetBg, divisor, context.glucose, base.temptargetSet, autosens.ratio), settings, parts)
        val selected = autosens.copy(ratio = BoostIsfPreparation.selectSensitivityRatio(
            settings.useTdd, settings.autosensWhenNoTdd, isf.ratio, autosens.ratio))
        val recovering = context.recoveryEnabled && context.atMs < context.recoveryEndMs
        val recoveryScale = if (recovering) context.recoveryScale else 1.0
        val exercising = context.activityState in setOf("ACTIVE", "VIGOROUS_AEROBIC", "MODERATE_AEROBIC",
            "LIGHT_AEROBIC", "RESISTANCE", "STRESS")
        val profile = V1ReplayProfile(
            dia = 0.0,
            min_5m_carbimpact = 0.0,
            max_iob = base.max_iob,
            max_daily_basal = base.max_daily_basal,
            max_basal = base.max_basal,
            min_bg = context.minBg,
            max_bg = context.maxBg,
            target_bg = context.targetBg,
            carb_ratio = base.carb_ratio,
            sens = base.sens,
            autosens_adjust_targets = false,
            max_daily_safety_multiplier = base.max_daily_safety_multiplier,
            current_basal_safety_multiplier = base.current_basal_safety_multiplier,
            high_temptarget_raises_sensitivity = settings.highTtRaisesSens,
            low_temptarget_lowers_sensitivity = settings.lowTtLowersSens,
            sensitivity_raises_target = true,
            resistance_lowers_target = true,
            adv_target_adjustments = true,
            exercise_mode = base.exercise_mode,
            half_basal_exercise_target = base.half_basal_exercise_target,
            maxCOB = base.maxCOB,
            skip_neutral_temps = base.skip_neutral_temps,
            remainingCarbsCap = base.remainingCarbsCap,
            enableUAM = base.enableUAM,
            A52_risk_enable = base.A52_risk_enable,
            SMBInterval = base.SMBInterval,
            enableSMB_with_COB = base.enableSMB_with_COB,
            enableSMB_with_temptarget = base.enableSMB_with_temptarget,
            allowSMB_with_high_temptarget = base.allowSMB_with_high_temptarget,
            enableSMB_always = base.enableSMB_always,
            enableSMB_after_carbs = base.enableSMB_after_carbs,
            maxSMBBasalMinutes = base.maxSMBBasalMinutes,
            maxUAMSMBBasalMinutes = base.maxUAMSMBBasalMinutes,
            bolus_increment = base.bolus_increment,
            carbsReqThreshold = base.carbsReqThreshold,
            current_basal = if (context.profilePercent != 100) base.current_basal * scale else base.current_basal,
            temptargetSet = context.temporaryTargetOverride ?: base.temptargetSet,
            autosens_max = base.autosens_max,
            out_units = base.out_units,
            lgsThreshold = base.lgsThreshold,
            variable_sens = isf.variableSens,
            insulinDivisor = divisor,
            TDD = isf.tdd,
            dynISFBgCap = settings.bgCap,
            dynISFBgCapped = isf.bgCapped,
            sensNormalTarget = isf.sensNormalTarget,
            normalTarget = settings.normalTarget,
            dynISFvelocity = isf.dynIsfVelocity,
            insulinPeak = peak,
            boostActive = context.boostActive,
            profileSwitch = context.profilePercent,
            boost_bolus = context.boostBolus * recoveryScale,
            boost_maxIOB = context.boostMaxIob,
            Boost_InsulinReq = context.insulinReqPercent,
            boost_scale = context.boostScale * recoveryScale,
            boost_percent_scale = context.percentScale,
            enableBoostPercentScale = context.enablePercentScale,
            enableCircadianISF = context.circadianIsf,
            allowBoost_with_high_temptarget = context.allowBoostWithHighTarget,
            recentSteps5Minutes = context.steps5,
            recentSteps15Minutes = context.steps15,
            recentSteps30Minutes = context.steps30,
            recentSteps60Minutes = context.steps60,
            recentLowBG = context.recentLowBg,
            recentBrakingProduct = context.brakingProduct,
            dynIsfMode = settings.useTdd,
            boostDebugReason = context.boostDebug,
            isfDebugReason = isf.isfDebug,
            v5_exerciseActive = exercising,
            v5_inPostExerciseWindow = recovering,
            v5_exerciseSubclass = context.activityState,
            dynamicCarbRatio = context.dynamicCarbRatio,
            dynamicCarbRatioReason = context.dynamicCarbRatioReason,
        )
        return Prepared(profile, selected, isf)
    }
}
