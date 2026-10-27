package app.aaps.plugins.boostport.telemetry

/**
 * Offline replay for consecutive caller-supplied V1 diagnostic and V5 shadow
 * snapshots. State is explicitly provided per frame; it is never persisted,
 * mutated or passed to a live APS/pump interface.
 */
internal object BoostV1V5OfflineReplaySequence {
    enum class Finding { EMPTY, INVALID_SEQUENCE, FRAME_REJECTED, READY }
    data class Report(
        val finding: Finding,
        val inspectedFrames: Int = 0,
        val firstInvalidIndex: Int? = null,
        val canDeliverInsulin: Boolean = false,
    )
    fun inspect(frames: List<BoostV1V5OfflinePairedReplay.Input>): Report {
        if (frames.isEmpty()) return Report(Finding.EMPTY)
        for ((index, frame) in frames.withIndex()) {
            if (frame.atMs <= 0L || (index > 0 && frame.atMs <= frames[index - 1].atMs))
                return Report(Finding.INVALID_SEQUENCE, index, index)
            val inspected = BoostV1V5OfflinePairedReplay.inspect(frame)
            if (inspected.finding != BoostV1V5OfflinePairedReplay.Finding.READY)
                return Report(Finding.FRAME_REJECTED, index, index)
        }
        return Report(Finding.READY, frames.size)
    }
}
