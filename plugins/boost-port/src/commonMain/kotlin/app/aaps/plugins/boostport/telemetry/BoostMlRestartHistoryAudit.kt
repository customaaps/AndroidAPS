package app.aaps.plugins.boostport.telemetry

/**
 * Passive restart-history compatibility inspection for the Boost V6 ML ring.
 * Caller supplies an already parsed historical field-map snapshot. No JSON
 * parser, Preferences read/write, live CGM/IOB, inference, APS, or pump access.
 *
 * Restoration only produces a diagnostic finding; it never decides insulin.
 */
internal object BoostMlRestartHistoryAudit {
    enum class Finding {
        EMPTY, CORRUPT, FUTURE_SNAPSHOT, STALE_RESTART,
        COLD_START, IRREGULAR_SPACING, NORMALIZED_HISTORY, COMPLETE
    }

    data class Result(
        val finding: Finding,
        val restoredCount: Int,
        val latestAgeMs: Long?,
    )

    fun inspect(rows: List<Map<String, String>>, restartTimeMs: Long): Result {
        if (restartTimeMs < 0L) return Result(Finding.CORRUPT, 0, null)
        if (rows.isEmpty()) return Result(Finding.EMPTY, 0, null)
        // Validate the entire raw series before classifying its clock.
        // A malformed row must not mask corruption behind a future timestamp.
        val format = BoostMlPersistedFormatAudit.inspect(rows)
        if (format.finding != BoostMlPersistedFormatAudit.Finding.MATCH &&
            format.finding != BoostMlPersistedFormatAudit.Finding.NORMALIZED_ON_RESTORE
        ) return Result(Finding.CORRUPT, 0, null)
        // Inspect validated raw timestamps before ring replay: close-spaced
        // snapshots may be replaced and otherwise hide future data.
        if (rows.any { row -> row["ts"]!!.toLong() > restartTimeMs })
            return Result(Finding.FUTURE_SNAPSHOT, 0, null)
        // Decode strictly, rejecting invalid rows rather than accepting a
        // partial history that could masquerade as a full six-cycle window.
        val ring = BoostMlRingSchemaAudit.decode(rows)
            ?: return Result(Finding.CORRUPT, 0, null)
        val restored = ring.snapshots()
        if (restored.isEmpty()) return Result(Finding.CORRUPT, 0, null)
        if (restored.any { it.timestampMs > restartTimeMs })
            return Result(Finding.FUTURE_SNAPSHOT, restored.size, null)
        val report = BoostMlLagContinuityAudit.inspect(restored, restartTimeMs)
        val finding = when (report.finding) {
            BoostMlLagContinuityAudit.Finding.EMPTY -> Finding.EMPTY
            BoostMlLagContinuityAudit.Finding.INVALID -> Finding.CORRUPT
            BoostMlLagContinuityAudit.Finding.FUTURE_DATA -> Finding.FUTURE_SNAPSHOT
            BoostMlLagContinuityAudit.Finding.STALE -> Finding.STALE_RESTART
            BoostMlLagContinuityAudit.Finding.COLD_START -> Finding.COLD_START
            BoostMlLagContinuityAudit.Finding.IRREGULAR_SPACING -> Finding.IRREGULAR_SPACING
            BoostMlLagContinuityAudit.Finding.COMPLETE -> Finding.COMPLETE
        }
        // A parser/replay that discarded or rewrote stored rows must not
        // silently be reported as a fully faithful six-cycle restore.
        val observed = if (finding == Finding.COMPLETE &&
            format.finding == BoostMlPersistedFormatAudit.Finding.NORMALIZED_ON_RESTORE
        ) Finding.NORMALIZED_HISTORY else finding
        return Result(observed, restored.size, report.newestAgeMs)
    }
}
