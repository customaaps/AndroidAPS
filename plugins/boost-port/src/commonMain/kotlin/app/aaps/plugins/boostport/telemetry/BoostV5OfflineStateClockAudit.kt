package app.aaps.plugins.boostport.telemetry

/**
 * Offline, caller-fed compatibility check for state clocks versus a replay frame.
 * Does NOT reset a persisted meal state, unlock a commit, invoke APS, or affect insulin.
 */
internal object BoostV5OfflineStateClockAudit {
    enum class Finding { CONSISTENT, INVALID_FRAME_TIME, INVALID_STATE, FUTURE_STATE_CLOCK }
    data class Report(val finding: Finding, val futureFields: Set<String>)

    fun inspect(frameTimeMs: Long, state: BoostV5StateSnapshotCodec.Snapshot): Report {
        if (frameTimeMs < 0L) return Report(Finding.INVALID_FRAME_TIME, emptySet())
        if (BoostV5StateSnapshotCodec.encode(state) == null)
            return Report(Finding.INVALID_STATE, emptySet())

        val meal = state.meal
        // 0L is the archived unset marker. Nonzero clocks from the future
        // must not be silently accepted as evidence from an earlier replay cycle.
        val clocks = mapOf(
            "mealHypothesisLastAgeMs" to meal.lastAgeMs,
            "lastCommitMs" to meal.lastCommitMs,
            "nonPositiveRunStartMs" to meal.nonPositiveStartMs,
            "maxScoreAtMs" to meal.peakScoreAtMs,
            "maxOffsetAtMs" to meal.peakOffsetAtMs,
            "primerIobUpdatedMs" to state.primerIobUpdatedMs,
        )
        val future = clocks.filterValues { it > frameTimeMs }.keys
        return Report(
            if (future.isEmpty()) Finding.CONSISTENT else Finding.FUTURE_STATE_CLOCK,
            future,
        )
    }
}
