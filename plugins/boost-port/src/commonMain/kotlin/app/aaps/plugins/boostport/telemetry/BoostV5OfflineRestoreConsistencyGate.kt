package app.aaps.plugins.boostport.telemetry

/**
 * Cross-check independent offline restore inspectors before interpreting a replay.
 * A clean format/clock/ordering report does NOT certify clinical or engine parity.
 * There are no Android dependencies, side effects or live APS calls.
 */
internal object BoostV5OfflineRestoreConsistencyGate {
    enum class Finding { EMPTY, INVALID, CONSISTENT }
    data class Report(
        val finding: Finding,
        val firstInvalidIndex: Int?,
        val changedFrames: Int,
    )

    fun inspect(frames: List<BoostV5RestoreTransitionLedger.Frame>): Report {
        val window = BoostV5OfflineRestoreWindowAudit.inspect(frames)
        if (window.finding == BoostV5OfflineRestoreWindowAudit.Finding.EMPTY) {
            return Report(Finding.EMPTY, null, 0)
        }
        if (window.finding != BoostV5OfflineRestoreWindowAudit.Finding.VALID) {
            return Report(Finding.INVALID, window.firstInvalidIndex, 0)
        }
        val batch = BoostV5OfflineRestoreBatch.inspect(
            frames.map { BoostV5OfflineRestoreBatch.Frame(it.atMs, it.fields) }
        )
        if (!batch.chronological || batch.rejected != 0 || batch.absent != 0 ||
            batch.ready != frames.size
        ) return Report(Finding.INVALID, null, 0)
        return Report(Finding.CONSISTENT, null, window.changedFrameCount)
    }
}
