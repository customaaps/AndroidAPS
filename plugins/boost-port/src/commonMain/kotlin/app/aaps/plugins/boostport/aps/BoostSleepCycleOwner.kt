package app.aaps.plugins.boostport.aps

import app.aaps.core.data.model.HR
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.plugins.aps.openAPSBoost.activity.DailyStepHistoryTracker
import app.aaps.plugins.aps.openAPSBoost.activity.SleepHistoryTracker
import app.aaps.plugins.aps.openAPSBoost.activity.SleepStateDetector
import app.aaps.plugins.aps.openAPSBoost.activity.WearStepSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Sequential sleep/history evaluation before activity, night policy and V5.
 * The meal likelihood is from the previous completed calculation, as in source 6.2.
 * This owner is confined to the calculator mutex and its invalidatable store.
 */
internal class BoostSleepCycleOwner(
    private val persistence: PersistenceLayer,
    private val storage: KeyValueStore,
) {
    data class Settings(
        val nightStartMin: Int,
        val nightEndMin: Int,
        val restingHr: Int,
        val preSleepLeadMin: Int,
        val sleepHysteresisMin: Int,
        val wakeHysteresisMin: Int,
        val sleepInSteps: Int,
        val sleepInWindowMin: Int,
    )

    data class Snapshot(
        val state: SleepStateDetector.State,
        val lastBoundaryExitMs: Long?,
        val learnedDaytimeHrBpm: Int?,
        val lastGenuineWakeMs: Long = 0L,
    )

    data class Update(
        val evaluation: SleepStateDetector.Result,
        val learned: SleepHistoryTracker.Aggregate,
        val effectiveNightStartMin: Int,
        val effectiveNightEndMin: Int,
        val stepsToday: Int,
        /** Same captured query used by the detector; never reread for telemetry. */
        val heartRates: List<HR>,
        val stepsLast15Min: Int = 0,
    )

    private var state: SleepStateDetector.State? = null
    private var history: SleepHistoryTracker.History? = null
    private var lastBoundaryExitMs: Long? = null
    private var learnedDaytimeHrBpm: Int? = null
    private var lastGenuineWakeMs = 0L

    fun snapshot(): Snapshot {
        if (state == null) {
            val sleep = storage.getString(BoostApsSettingKeys.ApsBoostSleepState.key, "")
            val past = storage.getString(BoostApsSettingKeys.ApsBoostSleepHistory.key, "")
            validateStored(sleep) { SleepStateDetector.State.deserialize(it).serialize() }
            validateStored(past) { SleepHistoryTracker.History.deserialize(it).serialize() }
            // Assign together: a failing restore must not leave a half-loaded owner.
            state = SleepStateDetector.State.deserialize(sleep)
            history = SleepHistoryTracker.History.deserialize(past)
        }
        return Snapshot(checkNotNull(state).copy(stepSamples = checkNotNull(state).stepSamples.toMutableList(),
            phoneStepSamples = checkNotNull(state).phoneStepSamples.toMutableList()),
            lastBoundaryExitMs, learnedDaytimeHrBpm, lastGenuineWakeMs)
    }

    suspend fun evaluateCycle(
        nowMs: Long,
        offsetMinutes: Int,
        settings: Settings,
        stepsLast15Min: Int,
        mealLikelihood: Double?,
        cycleSpacingMinutes: Double? = null,
        phoneStepsToday: Int = 0,
    ): Update {
        require(nowMs > 30 * 60_000L && offsetMinutes in -1440..1440)
        require(settings.nightStartMin in 0..1439 && settings.nightEndMin in 0..1439 &&
            settings.restingHr > 0 && settings.preSleepLeadMin in 0..1439 &&
            settings.sleepHysteresisMin >= 0 && settings.wakeHysteresisMin >= 0 &&
            settings.sleepInSteps >= 0 && settings.sleepInWindowMin in 0..1439)
        require(stepsLast15Min >= 0 && phoneStepsToday >= 0)
        require(mealLikelihood == null || (mealLikelihood.isFinite() && mealLikelihood in 0.0..1.0))
        require(cycleSpacingMinutes == null || (cycleSpacingMinutes.isFinite() && cycleSpacingMinutes > 0.0))
        val previous = snapshot().state
        val past = checkNotNull(history)
        val offsetMs = offsetMinutes * 60_000L
        val learned = SleepHistoryTracker.aggregate(past, offsetMs)
        val start = clampToConfiguredBand(learned.sleepStartMinAvg, settings.nightStartMin)
        val end = clampToConfiguredBand(learned.wakeMinAvg, settings.nightEndMin, allowEarlier = false)
        val recentHr = persistence.getHeartRatesFromTimeToTime(nowMs - 30 * 60_000L, nowMs).map { it.copy() }
        val dayStart = DailyStepHistoryTracker.dayIndex(nowMs, offsetMs) * 86_400_000L - offsetMs
        // A failed read is not an empty day. Exceptions/cancellation reach the APS boundary.
        val rows = persistence.getStepsCountFromTimeToTime(dayStart, nowMs)
        val watchStepsToday = WearStepSource.stepsToday(rows, dayStart, nowMs)
        // Keep cumulative series separate: max(phone, watch) can hide every watch increment.
        // The latest timestamp can have one SC row per window in the target sampler.
        val latestStepTime = rows.filter { it.timestamp >= nowMs - 15 * 60_000L }.maxOfOrNull { it.timestamp }
        val watchSteps15 = latestStepTime?.let { at -> rows.filter { it.timestamp == at }.maxOf { it.steps15min } } ?: 0
        val combinedSteps15 = maxOf(stepsLast15Min, watchSteps15)
        val stepsToday = maxOf(watchStepsToday, phoneStepsToday)
        val minute = (((nowMs / 60_000L + offsetMinutes) % 1440 + 1440) % 1440).toInt()
        val evaluated = SleepStateDetector.evaluate(previous, SleepStateDetector.Inputs(
            nowMs = nowMs, minuteOfDay = minute, hrReadings = recentHr,
            hrWindowMinutes = 5, hrResting = learned.restingHrBpm ?: settings.restingHr,
            stepsLast15Min = combinedSteps15, mlMealLikely = mealLikelihood,
            nightStartMin = start, nightEndMin = end, preSleepLeadMin = settings.preSleepLeadMin,
            minSleepHysteresisMin = settings.sleepHysteresisMin,
            wakeHrHysteresisMin = settings.wakeHysteresisMin,
            stepsToday = watchStepsToday, sleepInStepsThreshold = settings.sleepInSteps,
            sleepInWindowMin = settings.sleepInWindowMin, cycleSpacingMinutes = cycleSpacingMinutes,
            stepsTodayPhone = phoneStepsToday,
        ))
        var nextHistory = past
        if (evaluated.transitioned) {
            nextHistory = when {
                previous.state != SleepStateDetector.SleepState.SLEEPING &&
                    evaluated.newState.state == SleepStateDetector.SleepState.SLEEPING ->
                    SleepHistoryTracker.onSleepStart(past, nowMs)
                previous.state == SleepStateDetector.SleepState.SLEEPING &&
                    evaluated.newState.state != SleepStateDetector.SleepState.SLEEPING -> {
                    val sleepStart = past.openSleepStartMs ?: 0L
                    val sleeping = if (sleepStart > 0L) heartRates(sleepStart, nowMs) else emptyList()
                    val priorWake = SleepHistoryTracker.lastWakeMs(past)
                    val daytime = if (priorWake != null && sleepStart > priorWake)
                        heartRates(priorWake, sleepStart) else emptyList()
                    SleepHistoryTracker.onWake(past, nowMs, sleeping, daytime, evaluated.wakeReason)
                }
                else -> past
            }
            // One storage edit under BoostOwnerStorage's import/close lock. Do not
            // advance the in-process snapshot if this write fails.
            storage.edit {
                putString(BoostApsSettingKeys.ApsBoostSleepState.key, evaluated.newState.serialize())
                if (nextHistory !== past)
                    putString(BoostApsSettingKeys.ApsBoostSleepHistory.key, nextHistory.serialize())
            }
        }
        state = evaluated.newState
        history = nextHistory
        if (evaluated.wakeReason == "boundary") lastBoundaryExitMs = nowMs
        if (evaluated.wakeReason == "steps" || evaluated.wakeReason == "hr_steps") lastGenuineWakeMs = nowMs
        learnedDaytimeHrBpm = learned.daytimeHrBpm
        return Update(evaluated, learned, start, end, stepsToday, recentHr.toList(), combinedSteps15)
    }

    private suspend fun heartRates(startMs: Long, endMs: Long) =
        persistence.getHeartRatesFromTimeToTime(startMs, endMs)
            .filter { it.isValid && it.beatsPerMinute > 0.0 }.map { it.beatsPerMinute }

    companion object {
        /** Source ±90-minute circular band; wake learning may only move later. */
        internal fun clampToConfiguredBand(learned: Int?, configured: Int, allowEarlier: Boolean = true): Int {
            if (learned == null) return configured
            val delta = ((learned - configured + 1440 + 720) % 1440) - 720
            val clamped = delta.coerceIn(if (allowEarlier) -90 else 0, 90)
            return ((configured + clamped) % 1440 + 1440) % 1440
        }

        /** Keep legacy omitted defaults, but never silently reset a corrupt restore. */
        private fun validateStored(raw: String, canonical: (String) -> String) {
            if (raw.isEmpty()) return
            require(raw.isNotBlank() && raw.length <= 1_048_576) { "invalid Boost sleep state" }
            val supplied = Json.parseToJsonElement(raw).jsonObject
            val decoded = Json.parseToJsonElement(canonical(raw)).jsonObject
            require(supplied.all { (key, value) ->
                decoded[key] == value ||
                    (key == "sleepEntryReason" && value == JsonNull && decoded[key] == JsonPrimitive("null"))
            }) { "corrupt or unsupported Boost sleep state" }
        }
    }
}
