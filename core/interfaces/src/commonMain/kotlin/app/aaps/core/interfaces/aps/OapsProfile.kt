package app.aaps.core.interfaces.aps

import kotlinx.serialization.Serializable

@Serializable
data class OapsProfile(
    var dia: Double, // Captured insulin DIA in hours; only AMA uses it in determine-basal.
    var min_5m_carbimpact: Double, // AMA only
    var max_iob: Double,
    var max_daily_basal: Double,
    var max_basal: Double,
    var min_bg: Double,
    var max_bg: Double,
    var target_bg: Double,
    var carb_ratio: Double,
    var sens: Double,
    var autosens_adjust_targets: Boolean, // AMA only
    var max_daily_safety_multiplier: Double,
    var current_basal_safety_multiplier: Double,
    var high_temptarget_raises_sensitivity: Boolean,
    var low_temptarget_lowers_sensitivity: Boolean,
    var sensitivity_raises_target: Boolean,
    var resistance_lowers_target: Boolean,
    var adv_target_adjustments: Boolean,
    var exercise_mode: Boolean,
    var half_basal_exercise_target: Int,
    var maxCOB: Int,
    var skip_neutral_temps: Boolean,
    var remainingCarbsCap: Int,
    var enableUAM: Boolean,
    var A52_risk_enable: Boolean,
    var SMBInterval: Int,
    var enableSMB_with_COB: Boolean,
    var enableSMB_with_temptarget: Boolean,
    var allowSMB_with_high_temptarget: Boolean,
    var enableSMB_always: Boolean,
    var enableSMB_after_carbs: Boolean,
    var maxSMBBasalMinutes: Int,
    var maxUAMSMBBasalMinutes: Int,
    var bolus_increment: Double,
    var carbsReqThreshold: Int,
    var current_basal: Double,
    var temptargetSet: Boolean,
    var autosens_max: Double,
    var out_units: String,
    var lgsThreshold: Int?,
    //DynISF only
    var variable_sens: Double,
    var insulinDivisor: Int,
    var TDD: Double,
    /**
     * DynISF base of the RAW TDD curve: `1800 / (TDD * ln(100 / divisor + 1))`, unrounded. It is
     * what the 3.x predictions (`future_sens` and the prediction BGI terms) computed inline from
     * `TDD` and the divisor, so it carries no velocity blend, no BG cap, no temp-target scaling,
     * no profile-% multiplier and no activity multiplier - only [variable_sens] sees those.
     */
    var dynIsfBaseSens: Double = 0.0,
    /**
     * DynISF only, null for the plugins that do not use DynISF (AMA). True when predictions and
     * `future_sens` use the raw TDD curve instead of [variable_sens], as in 3.x. Off by the
     * "depend on profile sensitivity" option, where the TDD curve is not the base at all.
     */
    var use_TDD_for_predictions: Boolean? = null
)