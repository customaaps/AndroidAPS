package app.aaps.plugins.boostport.telemetry

/**
 * Side-effect-free preflight for caller-provided V5 persisted key/value data.
 * Never reads Preferences or activates APS, SMB, or pump control.
 */
internal object BoostV5OfflineRestorePreflight {
    enum class Finding { READY, ABSENT, CORRUPT, INVALID_REPLAY_TIME, FUTURE_CLOCK }
    data class Report(val finding: Finding, val futureFields: Set<String> = emptySet())

    fun inspect(fields: Map<String, String>?, replayTimeMs: Long): Report {
        if (replayTimeMs < 0L) return Report(Finding.INVALID_REPLAY_TIME)
        if (fields == null || fields.isEmpty()) return Report(Finding.ABSENT)
        val snapshot = BoostV5StateSnapshotCodec.decode(fields)
            ?: return Report(Finding.CORRUPT)
        val clock = BoostV5OfflineStateClockAudit.inspect(replayTimeMs, snapshot)
        return when (clock.finding) {
            BoostV5OfflineStateClockAudit.Finding.CONSISTENT -> Report(Finding.READY)
            BoostV5OfflineStateClockAudit.Finding.FUTURE_STATE_CLOCK ->
                Report(Finding.FUTURE_CLOCK, clock.futureFields)
            BoostV5OfflineStateClockAudit.Finding.INVALID_FRAME_TIME ->
                Report(Finding.INVALID_REPLAY_TIME)
            BoostV5OfflineStateClockAudit.Finding.INVALID_STATE -> Report(Finding.CORRUPT)
        }
    }
}
