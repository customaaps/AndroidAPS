package app.aaps.plugins.boostport.telemetry

/**
 * Inert offline sequence check for independently validated V5 profile frames.
 * Does not execute APS, calculate a dose, read preferences, or control a pump.
 */
internal object BoostV5OfflineProfileReplayTimeline {
    enum class Finding { READY, EMPTY, INVALID_FRAME, NON_MONOTONIC_TIME, TOO_MANY_FRAMES }
    data class Frame(
        val timestampMs: Long,
        val profile: BoostV5OfflineProfileAssembly.Report,
    )
    data class Report(
        val finding: Finding,
        val inspectedFrames: Int,
        val firstInvalidIndex: Int?,
        val profileChanges: Int,
    )

    private const val MAX_OFFLINE_FRAMES = 10_000

    fun inspect(frames: List<Frame>): Report {
        if (frames.size > MAX_OFFLINE_FRAMES) return Report(Finding.TOO_MANY_FRAMES, 0, null, 0)
        if (frames.isEmpty()) return Report(Finding.EMPTY, 0, null, 0)
        var changes = 0
        for (i in frames.indices) {
            val frame = frames[i]
            if (frame.timestampMs < 0L) {
                return Report(Finding.NON_MONOTONIC_TIME, i + 1, i, changes)
            }
            if (i > 0) {
                val previous = frames[i - 1]
                if (frame.timestampMs <= previous.timestampMs) {
                    return Report(Finding.NON_MONOTONIC_TIME, i + 1, i, changes)
                }
            }
            if (frame.profile.snapshot == null) {
                return Report(Finding.INVALID_FRAME, i + 1, i, changes)
            }
            if (i > 0) {
                val previous = frames[i - 1]
                if (BoostV5OfflineProfileFingerprint.compare(previous.profile, frame.profile).finding ==
                    BoostV5OfflineProfileFingerprint.Finding.CHANGED
                ) changes++
            }
        }
        return Report(Finding.READY, frames.size, null, changes)
    }
}
