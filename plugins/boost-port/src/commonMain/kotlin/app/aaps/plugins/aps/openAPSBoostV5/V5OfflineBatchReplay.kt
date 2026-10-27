package app.aaps.plugins.aps.openAPSBoostV5

/**
 * Batch preflight for historical V5/V1 replay fixtures.
 * Pure inspection: no callbacks, APS registration, Preferences or pump access.
 * Every snapshot is independently validated; failure stops the batch.
 */
internal object V5OfflineBatchReplay {
    data class Frame(
        val persistedFields: Map<String, String>?,
        val v1InputsAvailable: Boolean,
        val fallbackAvailable: Boolean,
        val v5EngineAvailable: Boolean
    )

    enum class Finding { EMPTY_BATCH, FRAME_REJECTED, OFFLINE_BATCH_READY }

    data class Report(
        val finding: Finding,
        val acceptedFrames: Int,
        val rejectedFrameIndex: Int? = null,
        val liveInvocationAllowed: Boolean = false,
        val insulinDeliveryAllowed: Boolean = false
    )

    fun inspect(frames: List<Frame>): Report {
        if (frames.isEmpty()) return Report(Finding.EMPTY_BATCH, 0)
        for ((index, frame) in frames.withIndex()) {
            val report = V5OfflineAndroidBoundary.inspect(
                persistedFields = frame.persistedFields,
                v1EngineInputsAvailable = frame.v1InputsAvailable,
                fallbackAvailable = frame.fallbackAvailable,
                v5EngineAvailable = frame.v5EngineAvailable
            )
            if (report.finding != V5OfflineAndroidBoundary.Finding.OFFLINE_READY) {
                return Report(Finding.FRAME_REJECTED, index, index)
            }
        }
        return Report(Finding.OFFLINE_BATCH_READY, frames.size)
    }
}
