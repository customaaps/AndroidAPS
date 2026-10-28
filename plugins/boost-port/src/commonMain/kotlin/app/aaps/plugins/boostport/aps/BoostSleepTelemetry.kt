package app.aaps.plugins.boostport.aps

import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.utils.Round
import app.aaps.plugins.aps.openAPSBoost.HrSourceResolver
import app.aaps.plugins.aps.openAPSBoost.activity.SleepStateDetector

/** Source end-of-cycle upload snapshot; no new DB query or effect on the dosing candidate. */
internal object BoostSleepTelemetry {
    fun write(result: RT, update: BoostSleepCycleOwner.Update, nowMs: Long): HrSourceResolver.Resolution {
        val state = update.evaluation.newState
        val learned = update.learned // source aggregates BEFORE recording this cycle's transition
        val rows = update.heartRates
        result.sleepState = state.state.name
        result.sleepStateEnteredAtMs = state.enteredAtMs.takeIf { it > 0L }
        result.sleepEntryReason = state.sleepEntryReason
        result.sleepLearnedStartMin = learned.sleepStartMinAvg
        result.sleepLearnedWakeMin = learned.wakeMinAvg
        result.sleepLearnedDurationMin = learned.sleepDurationMinAvg
        result.sleepLearnedSessionCount = learned.sessionCount
        result.hrLearnedRestingBpm = learned.restingHrBpm
        result.hrLearnedDaytimeBpm = learned.daytimeHrBpm
        val latest = rows.maxByOrNull { it.timestamp }
        val avg5 = SleepStateDetector.averageHr(rows, nowMs, 5)
        val avg15 = SleepStateDetector.averageHr(rows, nowMs, 15)
        // Do not resurrect an older valid row when the newest row is invalid.
        result.hrBpmLatest = latest?.takeIf { it.isValid }?.beatsPerMinute?.let(::rounded)
        result.hrBpmAvg5m = avg5?.let(::rounded)
        result.hrBpmAvg15m = avg15?.let(::rounded)
        result.hrReadingsCount15m = rows.count { it.isValid && it.timestamp > nowMs - 15 * 60_000L }
        val source = HrSourceResolver.resolve(rows.filter { it.isValid }
            .map { HrSourceResolver.Reading(it.device, it.timestamp) }, nowMs)
        result.hrSource_resolved = source.active
        result.hrSource_states = source.note
        return source
    }

    private fun rounded(value: Double): Double {
        require(value.isFinite()) { "nonfinite Boost HR telemetry" }
        return Round.roundTo(value, 0.1)
    }
}
