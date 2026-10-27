package app.aaps.plugins.boostport.telemetry

/**
 * Read-only bridge between a caller-supplied persisted V5 field map and the
 * already isolated replay-entry inspection. No Preferences, JSON I/O, APS,
 * insulin dosing, or pump integration.
 *
 * An unknown future field or corrupt snapshot is rejected, never silently
 * reset to a fresh session with a cleared commit lock.
 */
internal object BoostV5OfflinePersistedReplayEntry {
    enum class Finding { READY, INVALID_REPLAY, REJECTED_PERSISTED, INVALID_STATE_CLOCK }
    data class Report(
        val finding: Finding,
        val replayFinding: BoostV5OfflineProfileReplayTimeline.Finding,
        val shapeFinding: BoostV5PersistedStateShapeAudit.Finding?,
        val stateFinding: BoostV5OfflineReplayStateGate.Finding?,
    )

    fun inspect(
        frames: List<BoostV5OfflineProfileReplayTimeline.Frame>,
        persistedFields: Map<String, String>,
    ): Report {
        val replay = BoostV5OfflineProfileReplayTimeline.inspect(frames)
        if (replay.finding != BoostV5OfflineProfileReplayTimeline.Finding.READY) {
            return Report(Finding.INVALID_REPLAY, replay.finding, null, null)
        }
        val shape = BoostV5PersistedStateShapeAudit.inspect(persistedFields)
        val acceptable = shape.finding == BoostV5PersistedStateShapeAudit.Finding.CANONICAL ||
            shape.finding == BoostV5PersistedStateShapeAudit.Finding.LEGACY_DEFAULTS
        val snapshot = if (acceptable) BoostV5StateSnapshotCodec.decode(persistedFields) else null
        if (snapshot == null) {
            return Report(Finding.REJECTED_PERSISTED, replay.finding, shape.finding, null)
        }
        val state = BoostV5OfflineReplayStateGate.inspect(frames, snapshot)
        return Report(
            if (state.finding == BoostV5OfflineReplayStateGate.Finding.READY)
                Finding.READY else Finding.INVALID_STATE_CLOCK,
            replay.finding, shape.finding, state.finding,
        )
    }
}
