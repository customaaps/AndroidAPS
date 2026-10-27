package app.aaps.plugins.boostport.telemetry

/**
 * Caller-fed, offline-only ledger of changes between archived V5 state snapshots.
 * Never loads or writes persistent data and never connects to an APS or pump.
 */
internal object BoostV5RestoreTransitionLedger {
    enum class Finding { VALID, EMPTY, INVALID_TIMESTAMP, NON_MONOTONIC_TIME, INVALID_SNAPSHOT, FUTURE_STATE_CLOCK }
    data class Frame(val atMs: Long, val fields: Map<String, String>)
    data class Change(val frameIndex: Int, val changedKeys: Set<String>)
    data class Report(val finding: Finding, val changes: List<Change> = emptyList(), val index: Int? = null)

    fun inspect(frames: List<Frame>): Report {
        if (frames.isEmpty()) return Report(Finding.EMPTY)
        val parsed = ArrayList<Map<String, String>>(frames.size)
        for ((index, frame) in frames.withIndex()) {
            if (frame.atMs < 0L) return Report(Finding.INVALID_TIMESTAMP, index = index)
            if (index > 0 && frame.atMs <= frames[index - 1].atMs)
                return Report(Finding.NON_MONOTONIC_TIME, index = index)
            val state = BoostV5StateSnapshotCodec.decode(frame.fields)
                ?: return Report(Finding.INVALID_SNAPSHOT, index = index)
            if (BoostV5OfflineStateClockAudit.inspect(frame.atMs, state).finding !=
                BoostV5OfflineStateClockAudit.Finding.CONSISTENT)
                return Report(Finding.FUTURE_STATE_CLOCK, index = index)
            val canonical = BoostV5StateSnapshotCodec.encode(state)
                ?: return Report(Finding.INVALID_SNAPSHOT, index = index)
            parsed.add(canonical)
        }
        val changes = (1 until parsed.size).map { index ->
            val previous = parsed[index - 1]
            val current = parsed[index]
            Change(index, (previous.keys + current.keys).filterTo(mutableSetOf()) {
                previous[it] != current[it]
            })
        }
        return Report(Finding.VALID, changes)
    }
}
