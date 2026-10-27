package app.aaps.plugins.boostport.telemetry

/**
 * Offline-only continuity classification for a caller-supplied restore timeline.
 * No persistence, APS registration, therapy calculations or pump control.
 */
internal object BoostV5RestoreContinuityAudit {
    enum class Finding {
        EMPTY, CONSISTENT, NON_CHRONOLOGICAL, INVALID_SNAPSHOT,
        MISSING_SNAPSHOT, SESSION_LOCK_REGRESSION
    }

    data class Report(
        val finding: Finding,
        val problematicIndices: List<Int>,
        val inspectedFrames: Int,
    )

    fun inspect(frames: List<BoostV5OfflineRestoreBatch.Frame>): Report {
        if (frames.isEmpty()) return Report(Finding.EMPTY, emptyList(), 0)
        val batch = BoostV5OfflineRestoreBatch.inspect(frames)
        if (!batch.chronological) {
            val indices = (1 until frames.size).filter { frames[it].timeMs <= frames[it - 1].timeMs }
            return Report(Finding.NON_CHRONOLOGICAL, indices, frames.size)
        }
        val invalid = batch.entries.filter {
            it.finding != BoostV5OfflineRestorePreflight.Finding.READY &&
                it.finding != BoostV5OfflineRestorePreflight.Finding.ABSENT
        }.map { it.index }
        if (invalid.isNotEmpty()) return Report(Finding.INVALID_SNAPSHOT, invalid, frames.size)
        val absent = batch.entries.filter {
            it.finding == BoostV5OfflineRestorePreflight.Finding.ABSENT
        }.map { it.index }
        if (absent.isNotEmpty()) return Report(Finding.MISSING_SNAPSHOT, absent, frames.size)

        val snapshots = frames.map { frame ->
            // READY above guarantees a valid decode.
            requireNotNull(BoostV5StateSnapshotCodec.decode(requireNotNull(frame.persisted)))
        }
        val regressed = (1 until snapshots.size).filter { index ->
            val previous = snapshots[index - 1].meal
            val current = snapshots[index].meal
            previous.committedInSession && !current.committedInSession &&
                previous.lastCommitMs > 0L &&
                frames[index].timeMs - previous.lastCommitMs <
                    BoostV5MealStateDiagnostics.SESSION_LOCK_MIN_MS &&
                current.nonPositiveStartMs == 0L
        }
        if (regressed.isNotEmpty()) {
            return Report(Finding.SESSION_LOCK_REGRESSION, regressed, frames.size)
        }
        return Report(Finding.CONSISTENT, emptyList(), frames.size)
    }
}
