package app.aaps.plugins.boostport.telemetry

/**
 * Bounded offline replay window validation. No JSON/Preferences I/O or APS use.
 * Explicitly rejects overly large caller-provided windows before allocating
 * a transition ledger.
 */
internal object BoostV5OfflineRestoreWindowAudit {
    const val MAX_FRAMES = 1000
    enum class Finding { EMPTY, TOO_MANY_FRAMES, INVALID_WINDOW, VALID }
    data class Report(
        val finding: Finding,
        val frameCount: Int,
        val firstInvalidIndex: Int? = null,
        val changedFrameCount: Int = 0,
    )

    fun inspect(frames: List<BoostV5RestoreTransitionLedger.Frame>): Report {
        if (frames.isEmpty()) return Report(Finding.EMPTY, 0)
        if (frames.size > MAX_FRAMES) return Report(Finding.TOO_MANY_FRAMES, frames.size)
        val transitions = BoostV5RestoreTransitionLedger.inspect(frames)
        return if (transitions.finding == BoostV5RestoreTransitionLedger.Finding.VALID) {
            Report(
                finding = Finding.VALID,
                frameCount = frames.size,
                changedFrameCount = transitions.changes.count { it.changedKeys.isNotEmpty() },
            )
        } else Report(
            finding = Finding.INVALID_WINDOW,
            frameCount = frames.size,
            firstInvalidIndex = transitions.index,
        )
    }
}
