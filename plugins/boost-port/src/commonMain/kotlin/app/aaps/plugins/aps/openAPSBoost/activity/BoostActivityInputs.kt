package app.aaps.plugins.aps.openAPSBoost.activity

/** Resolved source settings; clock parsing/unit conversion belongs to the application boundary. */
internal data class BoostActivitySettings(
    val boostStartTime: String,
    val boostEndTime: String,
    val boostStartOfDayMs: Long,
    val boostEndOfDayMs: Long,
    val sleepInHours: Double,
    val sleepInSteps: Int,
    val allowBoostWithHighTt: Boolean,
    val dynIsfNormalTarget: Double,
    val inactivitySteps: Int,
    val inactivityPct: Double,
    val activitySteps5: Int,
    val activitySteps15: Int,
    val activitySteps30: Int,
    val activitySteps60: Int,
    val activityPct: Double,
    val hrIntegrationEnabled: Boolean,
    val hrWindowMinutes: Int,
    val hrMaxBpm: Int,
    val hrRestingBpm: Int,
    val hrStressDetection: Boolean,
    val sleepHysteresisMin: Int,
    val nightStartOfDayMs: Long,
    val nightEndOfDayMs: Long,
    val nightModeEnabled: Boolean = false,
    val autoBySleep: Boolean = false,
)

/** Current-cycle sleep/phone state and one captured local-clock interpretation. */
internal data class BoostActivitySnapshot(
    val dayStartMs: Long,
    val localTimeText: String,
    val phoneSteps5: Int,
    val phoneSteps15: Int,
    val phoneSteps30: Int,
    val phoneSteps60: Int,
    val phoneFeedLive: Boolean,
    val sleep: SleepStateDetector.State,
    val lastSleepBoundaryExitMs: Long?,
    val learnedDaytimeHrBpm: Int?,
    val effectiveNightEndMin: Int? = null,
    val lastGenuineWakeMs: Long = 0L,
)
