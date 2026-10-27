package app.aaps.plugins.aps.openAPSBoostV5

/**
 * Consolidated, read-only audit of historical V5 frames.
 * Both prerequisite gating and chronological continuity must pass.
 * No executable V1/V5 engine, Preferences, APS or pump dependency.
 */
internal object V5OfflineReplayBatchAudit {
    enum class Finding { EMPTY, PREREQUISITE_REJECTED, CONTINUITY_REJECTED, OFFLINE_AUDIT_PASSED }

    data class Report(
        val finding: Finding,
        val acceptedFrames: Int,
        val rejectedIndex: Int? = null,
        val liveInvocationAllowed: Boolean = false,
        val insulinDeliveryAllowed: Boolean = false
    )

    fun inspect(frames: List<V5OfflineBatchReplay.Frame>): Report {
        val prerequisites = V5OfflineBatchReplay.inspect(frames)
        if (prerequisites.finding == V5OfflineBatchReplay.Finding.EMPTY_BATCH) {
            return Report(Finding.EMPTY, 0)
        }
        if (prerequisites.finding != V5OfflineBatchReplay.Finding.OFFLINE_BATCH_READY) {
            return Report(Finding.PREREQUISITE_REJECTED, prerequisites.acceptedFrames, prerequisites.rejectedFrameIndex)
        }
        val continuity = V5OfflineReplayContinuity.inspect(frames.map { it.persistedFields })
        if (continuity.finding != V5OfflineReplayContinuity.Finding.OFFLINE_CONTINUITY_DESCRIBED) {
            return Report(Finding.CONTINUITY_REJECTED, continuity.inspectedFrames, continuity.rejectedIndex)
        }
        return Report(Finding.OFFLINE_AUDIT_PASSED, frames.size)
    }
}
