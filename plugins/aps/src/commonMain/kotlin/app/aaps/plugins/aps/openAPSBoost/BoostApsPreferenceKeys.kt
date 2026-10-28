package app.aaps.plugins.aps.openAPSBoost

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.UnitType
import app.aaps.core.keys.interfaces.*
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys

/** UI aliases preserve raw source keys/defaults/ranges; runtime never uses Simple Mode masking. */
internal object BoostApsPreferenceKeys {
    private class B(val raw: BoostApsSettingKeys.BooleanValue, override val title: TextRef) : BooleanPreferenceKey {
        override val key = raw.key
        override val defaultValue = raw.defaultValue
        override val preferenceType = PreferenceType.SWITCH
        override val showInNsClientMode = false
        override val showInPumpControlMode = false
        override val summary get() = ApsStrings.boost_floor_safety.takeIf {
            key == BoostApsSettingKeys.ApsBoostV5ComposedFloorActive.key || key == BoostApsSettingKeys.ApsBoostV5VelocityBudgetActive.key }
    }
    private class I(val raw: BoostApsSettingKeys.IntValue, override val title: TextRef,
        override val min: Int, override val max: Int) : IntPreferenceKey {
        override val key = raw.key
        override val defaultValue = raw.defaultValue
        override val showInNsClientMode = false
        override val showInPumpControlMode = false
    }
    // Keep hundredths visible (for example a saved 0.48 threshold), but nudge decimals by 0.1.
    private class D(val raw: BoostApsSettingKeys.DoubleValue, override val title: TextRef,
        override val min: Double, override val max: Double,
        override val unitType: UnitType = UnitType.DOUBLE_2) : DoublePreferenceKey {
        override val stepSize: Double get() = if (unitType == UnitType.NONE) 1.0 else 0.1
        override val key = raw.key
        override val defaultValue = raw.defaultValue
        override val calculatedBySM = false
        override val showInNsClientMode = false
        override val showInPumpControlMode = false
    }
    private class U(val raw: BoostApsSettingKeys.DoubleValue, override val title: TextRef,
        override val minMgdl: Int, override val maxMgdl: Int) : UnitDoublePreferenceKey {
        override val key = raw.key
        override val defaultValue = raw.defaultValue
        override val showInNsClientMode = false
        override val showInPumpControlMode = false
    }
    private class S(val raw: BoostApsSettingKeys.StringValue, override val title: TextRef) : StringPreferenceKey {
        override val key = raw.key
        override val defaultValue = raw.defaultValue
        override val isPassword = false
        override val isPin = false
        override val showInNsClientMode = false
        override val showInPumpControlMode = false
        override val validator = StringValidator { value ->
            if (Regex("(?:[01]?[0-9]|2[0-3]):[0-5][0-9]|24:00").matches(value)) StringValidator.ValidationResult.VALID
            else StringValidator.ValidationResult.invalid("HH:mm")
        }
    }
    // An offset can be below 20 mg/dL, where legacy unit guessing is ambiguous.
    // Render that one value explicitly in canonical mg/dL, without heuristic conversion.
    val editable: List<PreferenceKey> = listOf(
        B(BoostApsSettingKeys.ApsBoostEnablePercentScale, ApsStrings.boost_pref_enable_percent_scale),
        B(BoostApsSettingKeys.ApsBoostEnableCircadianIsf, ApsStrings.boost_pref_enable_circadian_isf),
        B(BoostApsSettingKeys.ApsBoostAllowWithHighTt, ApsStrings.boost_pref_allow_with_high_tt),
        B(BoostApsSettingKeys.ApsBoostUseTdd, ApsStrings.boost_pref_use_tdd),
        B(BoostApsSettingKeys.ApsBoostAdjustSensitivity, ApsStrings.boost_pref_adjust_sensitivity),
        B(BoostApsSettingKeys.ApsBoostNightModeEnabled, ApsStrings.boost_pref_night_mode_enabled),
        B(BoostApsSettingKeys.ApsBoostNightModeDisableWithCob, ApsStrings.boost_pref_night_mode_disable_with_cob),
        B(BoostApsSettingKeys.ApsBoostNightModeDisableWithLowTt, ApsStrings.boost_pref_night_mode_disable_with_low_tt),
        B(BoostApsSettingKeys.ApsBoostPostExerciseRecoveryEnabled, ApsStrings.boost_pref_post_exercise_recovery_enabled),
        B(BoostApsSettingKeys.ApsBoostHrIntegrationEnabled, ApsStrings.boost_pref_hr_integration_enabled),
        B(BoostApsSettingKeys.ApsBoostHrStressDetection, ApsStrings.boost_pref_hr_stress_detection),
        B(BoostApsSettingKeys.ApsBoostNightModeAutoBySleep, ApsStrings.boost_pref_night_mode_auto_by_sleep),
        B(BoostApsSettingKeys.ApsBoostHealthConnectHrEnabled, ApsStrings.boost_pref_health_connect_hr_enabled),
        B(BoostApsSettingKeys.ApsBoostV6PreMealTarget, ApsStrings.boost_pref_v6_pre_meal_target),
        B(BoostApsSettingKeys.ApsBoostV5FastCarbConfirm, ApsStrings.boost_pref_v5_fast_carb_confirm),
        B(BoostApsSettingKeys.ApsBoostV5ConfirmTranche, ApsStrings.boost_pref_v5_confirm_tranche),
        B(BoostApsSettingKeys.ApsBoostV5AggressiveEarlyConfirm, ApsStrings.boost_pref_v5_aggressive_early_confirm),
        B(BoostApsSettingKeys.ApsBoostV5ComposedFloorActive, ApsStrings.boost_pref_v5_composed_floor_active),
        B(BoostApsSettingKeys.ApsBoostV5VelocityBudgetActive, ApsStrings.boost_pref_v5_velocity_budget_active),
        B(BoostApsSettingKeys.ApsBoostV5PrimerBolusMode, ApsStrings.boost_pref_v5_primer_bolus_mode),
        B(BoostApsSettingKeys.ApsBoostPostRescueTightRampTrial, ApsStrings.boost_pref_post_rescue_tight_ramp_trial),
        B(BoostApsSettingKeys.ApsBoostAutosensWhenNoTdd, ApsStrings.boost_pref_autosens_when_no_tdd),
        I(BoostApsSettingKeys.ApsBoostInactivitySteps, ApsStrings.boost_pref_inactivity_steps, 0, 1000),
        I(BoostApsSettingKeys.ApsBoostSleepInSteps, ApsStrings.boost_pref_sleep_in_steps, 0, 1000),
        I(BoostApsSettingKeys.ApsBoostActivitySteps5, ApsStrings.boost_pref_activity_steps5, 0, 5000),
        I(BoostApsSettingKeys.ApsBoostActivitySteps15, ApsStrings.boost_pref_activity_steps15, 0, 10000),
        I(BoostApsSettingKeys.ApsBoostActivitySteps30, ApsStrings.boost_pref_activity_steps30, 0, 10000),
        I(BoostApsSettingKeys.ApsBoostActivitySteps60, ApsStrings.boost_pref_activity_steps60, 0, 10000),
        I(BoostApsSettingKeys.ApsBoostDynIsfAdjustmentFactor, ApsStrings.boost_pref_dyn_isf_adjustment_factor, 1, 300),
        I(BoostApsSettingKeys.ApsBoostHrMaxBpm, ApsStrings.boost_pref_hr_max_bpm, 150, 220),
        I(BoostApsSettingKeys.ApsBoostHrRestingBpm, ApsStrings.boost_pref_hr_resting_bpm, 30, 100),
        I(BoostApsSettingKeys.ApsBoostHrWindowMinutes, ApsStrings.boost_pref_hr_window_minutes, 5, 60),
        I(BoostApsSettingKeys.ApsBoostPostExerciseMinDuration, ApsStrings.boost_pref_post_exercise_min_duration, 1, 120),
        I(BoostApsSettingKeys.ApsBoostPreSleepLeadMin, ApsStrings.boost_pref_pre_sleep_lead_min, 0, 180),
        I(BoostApsSettingKeys.ApsBoostSleepHysteresisMin, ApsStrings.boost_pref_sleep_hysteresis_min, 5, 30),
        I(BoostApsSettingKeys.ApsBoostWakeHrHysteresisMin, ApsStrings.boost_pref_wake_hr_hysteresis_min, 2, 15),
        I(BoostApsSettingKeys.ApsBoostHealthConnectPollMin, ApsStrings.boost_pref_health_connect_poll_min, 1, 30),
        D(BoostApsSettingKeys.ApsBoostBolus, ApsStrings.boost_pref_bolus, 0.1, 10.0),
        D(BoostApsSettingKeys.ApsBoostMaxIob, ApsStrings.boost_pref_max_iob, 0.1, 12.0),
        D(BoostApsSettingKeys.ApsBoostInsulinReqPct, ApsStrings.boost_pref_insulin_req_pct, 30.0, 100.0, UnitType.NONE),
        D(BoostApsSettingKeys.ApsBoostScale, ApsStrings.boost_pref_scale, 0.1, 3.0),
        D(BoostApsSettingKeys.ApsBoostPercentScale, ApsStrings.boost_pref_percent_scale, 50.0, 500.0, UnitType.NONE),
        D(BoostApsSettingKeys.ApsBoostDynIsfVelocity, ApsStrings.boost_pref_dyn_isf_velocity, 0.0, 100.0, UnitType.NONE),
        D(BoostApsSettingKeys.ApsBoostSleepInHours, ApsStrings.boost_pref_sleep_in_hours, 0.0, 18.0),
        D(BoostApsSettingKeys.ApsBoostInactivityPct, ApsStrings.boost_pref_inactivity_pct, 100.0, 200.0, UnitType.NONE),
        D(BoostApsSettingKeys.ApsBoostActivityPct, ApsStrings.boost_pref_activity_pct, 30.0, 150.0, UnitType.NONE),
        D(BoostApsSettingKeys.ApsBoostPostExerciseRecoveryHours, ApsStrings.boost_pref_post_exercise_recovery_hours, 0.5, 8.0),
        D(BoostApsSettingKeys.ApsBoostPostExerciseRecoveryScale, ApsStrings.boost_pref_post_exercise_recovery_scale, 0.0, 1.0),
        D(BoostApsSettingKeys.ApsBoostV5Aggression, ApsStrings.boost_pref_v5_aggression, 0.7, 1.6),
        D(BoostApsSettingKeys.ApsBoostV5HypoCaution, ApsStrings.boost_pref_v5_hypo_caution, 1.0, 2.0),
        D(BoostApsSettingKeys.ApsBoostV5Sensitivity, ApsStrings.boost_pref_v5_sensitivity, 0.8, 1.2),
        D(BoostApsSettingKeys.ApsBoostCumulativeSmbCap60Min, ApsStrings.boost_pref_cumulative_smb_cap60_min, 0.0, 10.0),
        D(BoostApsSettingKeys.ApsBoostV5ConfirmedCapU, ApsStrings.boost_pref_v5_confirmed_cap_u, 0.0, 7.5),
        D(BoostApsSettingKeys.ApsBoostV5CommittedCapU, ApsStrings.boost_pref_v5_committed_cap_u, 0.0, 2.5),
        D(BoostApsSettingKeys.ApsBoostV5TrancheFraction, ApsStrings.boost_pref_v5_tranche_fraction, 0.3, 1.0),
        D(BoostApsSettingKeys.ApsBoostV5TrancheThreshold, ApsStrings.boost_pref_v5_tranche_threshold, 0.2, 0.8),
        D(BoostApsSettingKeys.ApsBoostV5PrimerCapU, ApsStrings.boost_pref_v5_primer_cap_u, 0.0, 2.5),
        D(BoostApsSettingKeys.ApsBoostV6PreMealTargetMgdl, ApsStrings.boost_pref_v6_pre_meal_target_mgdl, 65.0, 90.0, UnitType.NONE),
        D(BoostApsSettingKeys.ApsBoostV6PreMealLeadMin, ApsStrings.boost_pref_v6_pre_meal_lead_min, 30.0, 90.0, UnitType.NONE),
        U(BoostApsSettingKeys.ApsBoostDynIsfBgCap, ApsStrings.boost_pref_dyn_isf_bg_cap, 100, 300),
        U(BoostApsSettingKeys.ApsBoostDynIsfNormalTarget, ApsStrings.boost_pref_dyn_isf_normal_target, 70, 120),
        D(BoostApsSettingKeys.ApsBoostNightModeBgOffset, ApsStrings.boost_pref_night_mode_bg_offset, 0.0, 90.0, UnitType.NONE),
        U(BoostApsSettingKeys.ApsBoostPostExerciseRecoveryTarget, ApsStrings.boost_pref_post_exercise_recovery_target, 90, 200),
        S(BoostApsSettingKeys.ApsBoostNightModeStart, ApsStrings.boost_pref_night_mode_start),
        S(BoostApsSettingKeys.ApsBoostNightModeEnd, ApsStrings.boost_pref_night_mode_end),
    )
    fun owned(raw: List<NonPreferenceKey>): List<NonPreferenceKey> = raw.map { key -> editable.firstOrNull { it.key == key.key } ?: key }
    fun sections(): List<PreferenceSubScreenDef> {
        val map = editable.associateBy { it.key }
        return listOf(
            PreferenceSubScreenDef("boost_settings_base", ApsStrings.boost_settings_base, listOfNotNull(map[BoostApsSettingKeys.ApsBoostEnablePercentScale.key], map[BoostApsSettingKeys.ApsBoostAllowWithHighTt.key], map[BoostApsSettingKeys.ApsBoostBolus.key], map[BoostApsSettingKeys.ApsBoostMaxIob.key], map[BoostApsSettingKeys.ApsBoostInsulinReqPct.key], map[BoostApsSettingKeys.ApsBoostScale.key], map[BoostApsSettingKeys.ApsBoostPercentScale.key], map[BoostApsSettingKeys.ApsBoostCumulativeSmbCap60Min.key])),
            PreferenceSubScreenDef("boost_settings_sensitivity", ApsStrings.boost_settings_sensitivity, listOfNotNull(map[BoostApsSettingKeys.ApsBoostEnableCircadianIsf.key], map[BoostApsSettingKeys.ApsBoostUseTdd.key], map[BoostApsSettingKeys.ApsBoostAdjustSensitivity.key], map[BoostApsSettingKeys.ApsBoostAutosensWhenNoTdd.key], map[BoostApsSettingKeys.ApsBoostDynIsfAdjustmentFactor.key], map[BoostApsSettingKeys.ApsBoostDynIsfVelocity.key], map[BoostApsSettingKeys.ApsBoostDynIsfBgCap.key], map[BoostApsSettingKeys.ApsBoostDynIsfNormalTarget.key])),
            PreferenceSubScreenDef("boost_settings_activity", ApsStrings.boost_settings_activity, listOfNotNull(map[BoostApsSettingKeys.ApsBoostPostExerciseRecoveryEnabled.key], map[BoostApsSettingKeys.ApsBoostHrIntegrationEnabled.key], map[BoostApsSettingKeys.ApsBoostHrStressDetection.key], map[BoostApsSettingKeys.ApsBoostHealthConnectHrEnabled.key], map[BoostApsSettingKeys.ApsBoostInactivitySteps.key], map[BoostApsSettingKeys.ApsBoostActivitySteps5.key], map[BoostApsSettingKeys.ApsBoostActivitySteps15.key], map[BoostApsSettingKeys.ApsBoostActivitySteps30.key], map[BoostApsSettingKeys.ApsBoostActivitySteps60.key], map[BoostApsSettingKeys.ApsBoostHrMaxBpm.key], map[BoostApsSettingKeys.ApsBoostHrRestingBpm.key], map[BoostApsSettingKeys.ApsBoostHrWindowMinutes.key], map[BoostApsSettingKeys.ApsBoostPostExerciseMinDuration.key], map[BoostApsSettingKeys.ApsBoostHealthConnectPollMin.key], map[BoostApsSettingKeys.ApsBoostInactivityPct.key], map[BoostApsSettingKeys.ApsBoostActivityPct.key], map[BoostApsSettingKeys.ApsBoostPostExerciseRecoveryHours.key], map[BoostApsSettingKeys.ApsBoostPostExerciseRecoveryScale.key], map[BoostApsSettingKeys.ApsBoostPostExerciseRecoveryTarget.key])),
            PreferenceSubScreenDef("boost_settings_sleep", ApsStrings.boost_settings_sleep, listOfNotNull(map[BoostApsSettingKeys.ApsBoostNightModeEnabled.key], map[BoostApsSettingKeys.ApsBoostNightModeDisableWithCob.key], map[BoostApsSettingKeys.ApsBoostNightModeDisableWithLowTt.key], map[BoostApsSettingKeys.ApsBoostNightModeAutoBySleep.key], map[BoostApsSettingKeys.ApsBoostSleepInSteps.key], map[BoostApsSettingKeys.ApsBoostPreSleepLeadMin.key], map[BoostApsSettingKeys.ApsBoostSleepHysteresisMin.key], map[BoostApsSettingKeys.ApsBoostWakeHrHysteresisMin.key], map[BoostApsSettingKeys.ApsBoostSleepInHours.key], map[BoostApsSettingKeys.ApsBoostNightModeBgOffset.key], map[BoostApsSettingKeys.ApsBoostNightModeStart.key], map[BoostApsSettingKeys.ApsBoostNightModeEnd.key])),
            PreferenceSubScreenDef("boost_settings_v5", ApsStrings.boost_settings_v5, listOfNotNull(map[BoostApsSettingKeys.ApsBoostV6PreMealTarget.key], map[BoostApsSettingKeys.ApsBoostV5FastCarbConfirm.key], map[BoostApsSettingKeys.ApsBoostV5ConfirmTranche.key], map[BoostApsSettingKeys.ApsBoostV5AggressiveEarlyConfirm.key], map[BoostApsSettingKeys.ApsBoostV5ComposedFloorActive.key], map[BoostApsSettingKeys.ApsBoostV5VelocityBudgetActive.key], map[BoostApsSettingKeys.ApsBoostV5PrimerBolusMode.key], map[BoostApsSettingKeys.ApsBoostPostRescueTightRampTrial.key], map[BoostApsSettingKeys.ApsBoostV5Aggression.key], map[BoostApsSettingKeys.ApsBoostV5HypoCaution.key], map[BoostApsSettingKeys.ApsBoostV5Sensitivity.key], map[BoostApsSettingKeys.ApsBoostV5ConfirmedCapU.key], map[BoostApsSettingKeys.ApsBoostV5CommittedCapU.key], map[BoostApsSettingKeys.ApsBoostV5TrancheFraction.key], map[BoostApsSettingKeys.ApsBoostV5TrancheThreshold.key], map[BoostApsSettingKeys.ApsBoostV5PrimerCapU.key], map[BoostApsSettingKeys.ApsBoostV6PreMealTargetMgdl.key], map[BoostApsSettingKeys.ApsBoostV6PreMealLeadMin.key])),
        )
    }
}

