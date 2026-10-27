package app.aaps.plugins.boostport.telemetry

/**
 * Aggregate counters for validated, offline Boost V5 profile replay changes.
 * Deliberately does not invoke APS, calculate doses or write preferences.
 */
internal object BoostV5OfflineReplayChangeSummary {
    data class Report(
        val replayFinding: BoostV5OfflineProfileReplayTimeline.Finding,
        val validatedFrames: Int,
        val changedFrames: Int,
        val baseChanges: Int,
        val additionalChanges: Int,
        val simultaneousChanges: Int,
    )

    fun inspect(frames: List<BoostV5OfflineProfileReplayTimeline.Frame>): Report {
        // Use only validated change events; malformed replay must not leak partial totals.
        val events = BoostV5OfflineProfileChangeEvents.inspect(frames)
        if (events.replayFinding != BoostV5OfflineProfileReplayTimeline.Finding.READY) {
            return Report(events.replayFinding, 0, 0, 0, 0, 0)
        }
        val base = events.changes.count { it.baseChanged }
        val extra = events.changes.count { it.additionalChanged }
        val simultaneous = events.changes.count { it.baseChanged && it.additionalChanged }
        return Report(
            replayFinding = events.replayFinding,
            validatedFrames = events.validatedFrameCount,
            changedFrames = events.changes.size,
            baseChanges = base,
            additionalChanges = extra,
            simultaneousChanges = simultaneous,
        )
    }
}
