package app.aaps.plugins.boostport.telemetry

/**
 * Read-only change events for a bounded, validated offline V5 replay.
 * Generates diagnostics only: no APS invocation, dose calculation or state writes.
 */
internal object BoostV5OfflineProfileChangeEvents {
    data class Change(
        val frameIndex: Int,
        val timestampMs: Long,
        val baseChanged: Boolean,
        val additionalChanged: Boolean,
    )
    data class Result(
        val replayFinding: BoostV5OfflineProfileReplayTimeline.Finding,
        val changes: List<Change>,
        val validatedFrameCount: Int,
    )

    fun inspect(frames: List<BoostV5OfflineProfileReplayTimeline.Frame>): Result {
        val validation = BoostV5OfflineProfileReplayTimeline.inspect(frames)
        if (validation.finding != BoostV5OfflineProfileReplayTimeline.Finding.READY) {
            return Result(validation.finding, emptyList(), 0)
        }
        val changes = ArrayList<Change>()
        for (index in 1 until frames.size) {
            val delta = BoostV5OfflineProfileFingerprint.compare(
                frames[index - 1].profile, frames[index].profile
            )
            if (delta.finding == BoostV5OfflineProfileFingerprint.Finding.CHANGED) {
                changes.add(Change(index, frames[index].timestampMs,
                    delta.changedBase, delta.changedAdditional))
            }
        }
        return Result(validation.finding, changes, validation.inspectedFrames)
    }
}
