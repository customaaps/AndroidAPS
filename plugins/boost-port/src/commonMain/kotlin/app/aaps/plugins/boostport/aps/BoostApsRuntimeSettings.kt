package app.aaps.plugins.boostport.aps

import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostActivityPct
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostActivitySteps15
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostActivitySteps30
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostActivitySteps5
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostActivitySteps60
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostAllowWithHighTt
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostDynIsfNormalTarget
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostEndTime
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostHrIntegrationEnabled
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostHrMaxBpm
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostHrRestingBpm
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostHrStressDetection
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostHrWindowMinutes
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostInactivityPct
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostInactivitySteps
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostNightModeEnd
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostNightModeEnabled
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostHealthConnectHrEnabled
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostNightModeStart
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostPostExerciseRecoveryEnabled
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostPostRescueTightRampTrial
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostSleepHysteresisMin
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostSleepInHours
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostSleepInSteps
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostStartTime
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostV5ComposedFloorActive
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostV5VelocityBudgetActive
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.ApsBoostV6PreMealTarget
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.BooleanValue
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.DoubleValue
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.IntValue
import app.aaps.plugins.boostport.aps.BoostApsSettingKeys.StringValue
import app.aaps.plugins.aps.openAPSBoost.activity.BoostActivitySettings

/** Canonical stored mg/dL values; no UI-unit conversion or Simple Mode masking. */
internal class BoostApsRuntimeSettings(private val store: KeyValueStore) {
    fun b(k: BooleanValue) = store.getBoolean(k.key, k.defaultValue)
    fun i(k: IntValue) = store.getInt(k.key, k.defaultValue)
    fun d(k: DoubleValue) = store.getDouble(k.key, k.defaultValue).also { require(it.isFinite()) }
    fun s(k: StringValue) = store.getString(k.key, k.defaultValue)

    fun activity(): BoostActivitySettings = BoostActivitySettings(
        s(ApsBoostStartTime), s(ApsBoostEndTime), clock(s(ApsBoostStartTime)), clock(s(ApsBoostEndTime)),
        d(ApsBoostSleepInHours), i(ApsBoostSleepInSteps), b(ApsBoostAllowWithHighTt), d(ApsBoostDynIsfNormalTarget),
        i(ApsBoostInactivitySteps), d(ApsBoostInactivityPct), i(ApsBoostActivitySteps5), i(ApsBoostActivitySteps15),
        i(ApsBoostActivitySteps30), i(ApsBoostActivitySteps60), d(ApsBoostActivityPct),
        b(ApsBoostHrIntegrationEnabled), i(ApsBoostHrWindowMinutes), i(ApsBoostHrMaxBpm), i(ApsBoostHrRestingBpm),
        b(ApsBoostHrStressDetection), i(ApsBoostSleepHysteresisMin),
        clock(s(ApsBoostNightModeStart), "22:00"), clock(s(ApsBoostNightModeEnd), "07:00"),
    )

    /** These owners are deliberately not approximated while the APS adapter is under integration. */
    fun unsupportedEnabledFeatures(): List<String> = listOf(
        ApsBoostPostExerciseRecoveryEnabled, ApsBoostV6PreMealTarget, ApsBoostPostRescueTightRampTrial,
        ApsBoostV5ComposedFloorActive, ApsBoostV5VelocityBudgetActive,
        ApsBoostNightModeEnabled, ApsBoostHealthConnectHrEnabled,
    ).filter(::b).map { it.key }

    companion object {
        /** Source H:mm clock semantics, including SMART's 24:00 -> midnight. */
        fun clock(value: String, fallback: String = "00:00"): Long {
            fun parse(raw: String): Long? {
                if (!Regex("[0-9]{1,2}:[0-9]{2}").matches(raw)) return null
                val (h, m) = raw.split(':').map(String::toInt)
                if (h == 24 && m == 0) return 0L
                if (h !in 0..23 || m !in 0..59) return null
                return (h * 60L + m) * 60_000L
            }
            return parse(value) ?: checkNotNull(parse(fallback))
        }
    }
}
