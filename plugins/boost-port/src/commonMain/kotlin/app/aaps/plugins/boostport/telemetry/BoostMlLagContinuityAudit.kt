package app.aaps.plugins.boostport.telemetry

/**
 * Offline, read-only inspection of the V6 model's six-slot, five-minute lag grid.
 * This does NOT score a model or imply suitability for insulin decisions.
 * No live sources, persistence, APS registration or pump interaction.
 */
internal object BoostMlLagContinuityAudit {
    const val EXPECTED_SPACING_MS = BoostMlFeatureDiagnostics.LAG_SPACING_MS
    const val TOLERANCE_MS = 30_000L
    const val MAX_AGE_MS = BoostMlFeatureDiagnostics.STALE_AFTER_MS

    enum class Finding {
        EMPTY, INVALID, FUTURE_DATA, STALE, COLD_START, IRREGULAR_SPACING, COMPLETE
    }

    data class Report(
        val finding: Finding,
        val retainedRows: Int,
        val newestAgeMs: Long?,
        val observedSpacingsMs: List<Long>,
    )

    /** All values are caller-supplied historical snapshots; no clock is read here. */
    fun inspect(
        snapshots: List<BoostMlFeatureDiagnostics.Snapshot>,
        inspectionTimeMs: Long,
    ): Report {
        fun reject(reason: Finding) = Report(reason, snapshots.size, null, emptyList())
        if (inspectionTimeMs < 0L ||
            snapshots.size > BoostMlFeatureDiagnostics.LOOKBACK ||
            snapshots.any { s ->
                s.timestampMs <= 0L || !s.cgmMgdl.isFinite() ||
                    !s.iobIob.isFinite() || !s.iobActivity.isFinite() ||
                    !s.eventualBg.isFinite() || !s.recentSmbUnits60m.isFinite() ||
                    !s.minDelta.isFinite()
            }
        ) return reject(Finding.INVALID)
        if (snapshots.isEmpty()) return Report(Finding.EMPTY, 0, null, emptyList())
        if (snapshots.zipWithNext().any { (a, b) -> b.timestampMs <= a.timestampMs })
            return reject(Finding.INVALID)
        val newest = snapshots.last().timestampMs
        if (newest > inspectionTimeMs) return reject(Finding.FUTURE_DATA)
        val age = inspectionTimeMs - newest
        val gaps = snapshots.zipWithNext().map { (a, b) -> b.timestampMs - a.timestampMs }
        val finding = when {
            age > MAX_AGE_MS -> Finding.STALE
            gaps.any { it < EXPECTED_SPACING_MS - TOLERANCE_MS ||
                it > EXPECTED_SPACING_MS + TOLERANCE_MS } -> Finding.IRREGULAR_SPACING
            snapshots.size < BoostMlFeatureDiagnostics.LOOKBACK -> Finding.COLD_START
            else -> Finding.COMPLETE
        }
        return Report(finding, snapshots.size, age, gaps)
    }
}
