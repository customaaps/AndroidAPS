package app.aaps.plugins.boostport.aps

import app.aaps.core.keys.interfaces.*

/** Source key names/defaults for APS integration; UI exposure is added only as features are wired. */
object BoostApsSettingKeys {
    data class BooleanValue(override val key: String, override val defaultValue: Boolean) : BooleanNonPreferenceKey
    data class IntValue(override val key: String, override val defaultValue: Int) : IntNonPreferenceKey
    data class DoubleValue(override val key: String, override val defaultValue: Double) : DoubleNonPreferenceKey
    data class StringValue(override val key: String, override val defaultValue: String, override val exportable: Boolean = true) : StringNonPreferenceKey
    val ApsBoostEnablePercentScale = BooleanValue("enableBoostPercentScale", false)
    val ApsBoostEnableCircadianIsf = BooleanValue("enableCircadianISF", false)
    val ApsBoostAllowWithHighTt = BooleanValue("enableBoost_with_high_temptarget", false)
    val ApsBoostUseTdd = BooleanValue("boost_use_tdd", false)
    val ApsBoostAdjustSensitivity = BooleanValue("boost_adjust_sensitivity", false)
    val ApsBoostAllowAllBgSources = BooleanValue("boost_allow_all_bg_sources", true)
    val ApsBoostNightModeEnabled = BooleanValue("boost_night_mode_enabled", false)
    val ApsBoostNightModeDisableWithCob = BooleanValue("boost_night_mode_disable_with_cob", false)
    val ApsBoostNightModeDisableWithLowTt = BooleanValue("boost_night_mode_disable_with_low_tt", false)
    val ApsBoostBypassVersionCheck = BooleanValue("boost_bypass_version_check", true)
    val ApsBoostPostExerciseRecoveryEnabled = BooleanValue("boost_post_exercise_recovery_enabled", false)
    val ApsBoostHrIntegrationEnabled = BooleanValue("boost_hr_integration_enabled", false)
    val ApsBoostHrStressDetection = BooleanValue("boost_hr_stress_detection", false)
    val ApsBoostNightModeAutoBySleep = BooleanValue("boost_night_mode_auto_by_sleep", false)
    val ApsBoostHealthConnectHrEnabled = BooleanValue("boost_health_connect_hr_enabled", false)
    val ApsBoostV5ActiveDosing = BooleanValue("boost_v5_active_dosing", false)
    val ApsBoostV6PreMealTarget = BooleanValue("boost_v6_pre_meal_target", false)
    val ApsBoostV5FastCarbConfirm = BooleanValue("boost_v5_fast_carb_confirm", true)
    val ApsBoostV5ConfirmTranche = BooleanValue("boost_v5_confirm_tranche", false)
    val ApsBoostV5AggressiveEarlyConfirm = BooleanValue("boost_v5_aggressive_early_confirm", false)
    val ApsBoostV5ComposedFloorActive = BooleanValue("boost_v5_composed_floor_active", false)
    val ApsBoostV5VelocityBudgetActive = BooleanValue("boost_v5_velocity_budget_active", false)
    val ApsBoostV5PrimerTbrFallback = BooleanValue("boost_v5_primer_tbr_fallback", false)
    val ApsBoostV5PrimerBolusMode = BooleanValue("boost_v5_primer_bolus_mode", false)
    val ApsBoostPostRescueTightRampTrial = BooleanValue("boost_postrescue_tight_ramp_trial", false)
    val ApsBoostV5AutoConfigDone = BooleanValue("boost_v5_autoconfig_done", false)
    val ApsBoostActivityShadowEnabled = BooleanValue("boost_activity_shadow_enabled", true)
    val ApsBoostAutosensWhenNoTdd = BooleanValue("boost_autosens_when_no_tdd", false)
    val ApsBoostInactivitySteps = IntValue("boost_inactivity_steps", 500)
    val ApsBoostSleepInSteps = IntValue("boost_sleep_in_steps", 250)
    val ApsBoostActivitySteps5 = IntValue("boost_activity_steps_5", 420)
    val ApsBoostActivitySteps15 = IntValue("boost_activity_steps_15", 800)
    val ApsBoostActivitySteps30 = IntValue("boost_activity_steps_30", 1200)
    val ApsBoostActivitySteps60 = IntValue("boost_activity_steps_60", 1800)
    val ApsBoostDynIsfAdjustmentFactor = IntValue("boost_DynISFAdjust", 100)
    val ApsBoostHrMaxBpm = IntValue("boost_hr_max_bpm", 180)
    val ApsBoostHrRestingBpm = IntValue("boost_hr_resting_bpm", 60)
    val ApsBoostHrWindowMinutes = IntValue("boost_hr_window_minutes", 15)
    val ApsBoostPostExerciseMinDuration = IntValue("boost_post_exercise_min_duration", 10)
    val ApsBoostPreSleepLeadMin = IntValue("boost_pre_sleep_lead_min", 60)
    val ApsBoostSleepHysteresisMin = IntValue("boost_sleep_hysteresis_min", 10)
    val ApsBoostWakeHrHysteresisMin = IntValue("boost_wake_hr_hysteresis_min", 5)
    val ApsBoostHealthConnectPollMin = IntValue("boost_health_connect_poll_min", 5)
    val ApsBoostBolus = DoubleValue("boost_bolus_cap", 2.5)
    val ApsBoostMaxIob = DoubleValue("boost_max_iob", 1.0)
    val ApsBoostInsulinReqPct = DoubleValue("boost_insulin_req_pct", 50.0)
    val ApsBoostScale = DoubleValue("boost_scale_value", 1.0)
    val ApsBoostPercentScale = DoubleValue("boost_percent_scale_factor", 200.0)
    val ApsBoostDynIsfVelocity = DoubleValue("boost_dynisf_velocity", 100.0)
    val ApsBoostSleepInHours = DoubleValue("boost_sleep_in_hrs", 2.0)
    val ApsBoostInactivityPct = DoubleValue("boost_inactivity_pct", 130.0)
    val ApsBoostActivityPct = DoubleValue("boost_activity_pct", 80.0)
    val ApsBoostPostExerciseRecoveryHours = DoubleValue("boost_post_exercise_recovery_hours", 2.0)
    val ApsBoostPostExerciseRecoveryScale = DoubleValue("boost_post_exercise_recovery_scale", 0.5)
    val ApsBoostV5Aggression = DoubleValue("boost_v5_aggression", 1.0)
    val ApsBoostV5HypoCaution = DoubleValue("boost_v5_hypo_caution", 1.0)
    val ApsBoostV5Sensitivity = DoubleValue("boost_v5_sensitivity", 1.0)
    val ApsBoostCumulativeSmbCap60Min = DoubleValue("boost_cumulative_smb_cap_60min", 10.0)
    val ApsBoostV5ConfirmedCapU = DoubleValue("boost_v5_confirmed_cap_u", 2.5)
    val ApsBoostV5CommittedCapU = DoubleValue("boost_v5_committed_cap_u", 0.5)
    val ApsBoostV5TrancheFraction = DoubleValue("boost_v5_tranche_fraction", 0.5)
    val ApsBoostV5TrancheThreshold = DoubleValue("boost_v5_tranche_threshold", 0.48)
    val ApsBoostV5PrimerCapU = DoubleValue("boost_v5_primer_cap_u", 0.0)
    val ApsBoostV6PreMealTargetMgdl = DoubleValue("boost_v6_pre_meal_target_mgdl", 72.0)
    val ApsBoostV6PreMealLeadMin = DoubleValue("boost_v6_pre_meal_lead_min", 60.0)
    val ApsBoostDynIsfBgCap = DoubleValue("boost_dynisf_bg_cap", 210.0)
    val ApsBoostDynIsfNormalTarget = DoubleValue("boost_dynisf_normal_target", 99.0)
    val ApsBoostNightModeBgOffset = DoubleValue("boost_night_mode_bg_offset", 27.0)
    val ApsBoostPostExerciseRecoveryTarget = DoubleValue("boost_post_exercise_recovery_target", 144.0)
    val ApsBoostStartTime = StringValue("boost_start_time", "07:00")
    val ApsBoostEndTime = StringValue("boost_end_time", "07:01")
    val ApsBoostNightModeStart = StringValue("boost_night_mode_start", "22:00")
    val ApsBoostNightModeEnd = StringValue("boost_night_mode_end", "07:00")
    val ApsBoostV5State = StringValue("boost_v5_state", "")
    val ApsBoostIsfShadowState = StringValue("boost_isf_shadow_state", "")
    val ApsBoostVwaTddShadowState = StringValue("boost_vwa_tdd_shadow_state", "")
    val ApsBoostLastEngineMode = StringValue("boost_last_engine_mode", "", exportable = false)
    val ApsBoostLastUseTdd = StringValue("boost_last_use_tdd", "", exportable = false)
    val ApsBoostSleepState = StringValue("boost_sleep_state", "")
    val ApsBoostSleepHistory = StringValue("boost_sleep_history", "")
    val ApsBoostMlRingBuffer = StringValue("boost_ml_ring_buffer", "")
    val ApsBoostMealTimeHistory = StringValue("boost_meal_time_history", "")
    val ApsBoostDailyStepHistory = StringValue("boost_daily_step_history", "")
    val ApsBoostIntradayStepBank = StringValue("boost_intraday_step_bank", "")
    val ApsBoostTrialSeed = StringValue("boost_trial_seed", "")
    val ApsBoostV5RedriveBaseline = StringValue("boost_v5_redrive_baseline", "")
    val ApsBoostV5AutoConfigPending = StringValue("boost_v5_autoconfig_pending", "")
    val ApsBoostV5RedriveSummary = StringValue("boost_v5_redrive_summary", "")
    val ApsBoostV5RedriveHistory = StringValue("boost_v5_redrive_history", "")
    val ApsBoostHistorySyncSummary = StringValue("boost_history_sync_summary", "")
    val Session = StringValue("boost_aps_session_v1", "")
    val all: List<NonPreferenceKey> = listOf(
        ApsBoostEnablePercentScale,
        ApsBoostEnableCircadianIsf,
        ApsBoostAllowWithHighTt,
        ApsBoostUseTdd,
        ApsBoostAdjustSensitivity,
        ApsBoostAllowAllBgSources,
        ApsBoostNightModeEnabled,
        ApsBoostNightModeDisableWithCob,
        ApsBoostNightModeDisableWithLowTt,
        ApsBoostBypassVersionCheck,
        ApsBoostPostExerciseRecoveryEnabled,
        ApsBoostHrIntegrationEnabled,
        ApsBoostHrStressDetection,
        ApsBoostNightModeAutoBySleep,
        ApsBoostHealthConnectHrEnabled,
        ApsBoostV5ActiveDosing,
        ApsBoostV6PreMealTarget,
        ApsBoostV5FastCarbConfirm,
        ApsBoostV5ConfirmTranche,
        ApsBoostV5AggressiveEarlyConfirm,
        ApsBoostV5ComposedFloorActive,
        ApsBoostV5VelocityBudgetActive,
        ApsBoostV5PrimerTbrFallback,
        ApsBoostV5PrimerBolusMode,
        ApsBoostPostRescueTightRampTrial,
        ApsBoostV5AutoConfigDone,
        ApsBoostActivityShadowEnabled,
        ApsBoostAutosensWhenNoTdd,
        ApsBoostInactivitySteps,
        ApsBoostSleepInSteps,
        ApsBoostActivitySteps5,
        ApsBoostActivitySteps15,
        ApsBoostActivitySteps30,
        ApsBoostActivitySteps60,
        ApsBoostDynIsfAdjustmentFactor,
        ApsBoostHrMaxBpm,
        ApsBoostHrRestingBpm,
        ApsBoostHrWindowMinutes,
        ApsBoostPostExerciseMinDuration,
        ApsBoostPreSleepLeadMin,
        ApsBoostSleepHysteresisMin,
        ApsBoostWakeHrHysteresisMin,
        ApsBoostHealthConnectPollMin,
        ApsBoostBolus,
        ApsBoostMaxIob,
        ApsBoostInsulinReqPct,
        ApsBoostScale,
        ApsBoostPercentScale,
        ApsBoostDynIsfVelocity,
        ApsBoostSleepInHours,
        ApsBoostInactivityPct,
        ApsBoostActivityPct,
        ApsBoostPostExerciseRecoveryHours,
        ApsBoostPostExerciseRecoveryScale,
        ApsBoostV5Aggression,
        ApsBoostV5HypoCaution,
        ApsBoostV5Sensitivity,
        ApsBoostCumulativeSmbCap60Min,
        ApsBoostV5ConfirmedCapU,
        ApsBoostV5CommittedCapU,
        ApsBoostV5TrancheFraction,
        ApsBoostV5TrancheThreshold,
        ApsBoostV5PrimerCapU,
        ApsBoostV6PreMealTargetMgdl,
        ApsBoostV6PreMealLeadMin,
        ApsBoostDynIsfBgCap,
        ApsBoostDynIsfNormalTarget,
        ApsBoostNightModeBgOffset,
        ApsBoostPostExerciseRecoveryTarget,
        ApsBoostStartTime,
        ApsBoostEndTime,
        ApsBoostNightModeStart,
        ApsBoostNightModeEnd,
        ApsBoostV5State,
        ApsBoostIsfShadowState,
        ApsBoostVwaTddShadowState,
        ApsBoostLastEngineMode,
        ApsBoostLastUseTdd,
        ApsBoostSleepState,
        ApsBoostSleepHistory,
        ApsBoostMlRingBuffer,
        ApsBoostMealTimeHistory,
        ApsBoostDailyStepHistory,
        ApsBoostIntradayStepBank,
        ApsBoostTrialSeed,
        ApsBoostV5RedriveBaseline,
        ApsBoostV5AutoConfigPending,
        ApsBoostV5RedriveSummary,
        ApsBoostV5RedriveHistory,
        ApsBoostHistorySyncSummary,
        Session
    )
}
