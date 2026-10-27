package app.aaps.plugins.boostport.telemetry

/**
 * Offline-only acceptance gate for a V5 replay profile timeline together with
 * a persisted state snapshot. No state restoration, dosing or pump integration.
 */
internal object BoostV5OfflineReplayStateGate {
    enum class Finding { READY, INVALID_REPLAY, INVALID_STATE, FUTURE_STATE_CLOCK }

    data class Report(
        val finding: Finding,
        val replayFinding: BoostV5OfflineProfileReplayTimeline.Finding,
        val stateFinding: BoostV5OfflineStateClockAudit.Finding?,
        val futureClockFields: Set<String>,
    )

    fun inspect(
        frames: List<BoostV5OfflineProfileReplayTimeline.Frame>,
        state: BoostV5StateSnapshotCodec.Snapshot,
    ): Report {
        val replay = BoostV5OfflineProfileReplayTimeline.inspect(frames)
        if (replay.finding != BoostV5OfflineProfileReplayTimeline.Finding.READY) {
            return Report(Finding.INVALID_REPLAY, replay.finding, null, emptySet())
        }
        // Inspect state against the FIRST frame: otherwise a state from the
        // middle of a replay could leak future evidence into earlier cycles.
        val stateResult = BoostV5OfflineStateClockAudit.inspect(frames.first().timestampMs, state)
        val finding = when (stateResult.finding) {
            BoostV5OfflineStateClockAudit.Finding.CONSISTENT -> Finding.READY
            BoostV5OfflineStateClockAudit.Finding.FUTURE_STATE_CLOCK -> Finding.FUTURE_STATE_CLOCK
            BoostV5OfflineStateClockAudit.Finding.INVALID_FRAME_TIME,
            BoostV5OfflineStateClockAudit.Finding.INVALID_STATE -> Finding.INVALID_STATE
        }
        return Report(finding, replay.finding, stateResult.finding, stateResult.futureFields)
    }
}
