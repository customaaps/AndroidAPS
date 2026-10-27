package app.aaps.plugins.boostport.telemetry

/**
 * Offline interpretation of restored session locks with optional caller-supplied
 * evidence. Unlike a live state machine, this never changes a persisted state.
 * Missing delta history cannot prove a lock regression.
 */
internal object BoostV5RestoreEvidenceAudit {
    enum class Finding {
        NO_FRAMES, INVALID_SEQUENCE, NO_LOCK_DROP, LOCK_EXPIRED,
        EARLY_DROP_WITHOUT_EVIDENCE, EARLY_DROP_REQUIRES_REVIEW
    }
    data class Frame(
        val timeMs: Long,
        val stored: Map<String, String>,
        val nonPositiveDeltaContinuouslySinceMs: Long? = null,
    )
    data class Report(val finding: Finding, val indices: List<Int>)

    fun inspect(frames: List<Frame>): Report {
        if (frames.isEmpty()) return Report(Finding.NO_FRAMES, emptyList())
        val snapshots = frames.map { BoostV5StateSnapshotCodec.decode(it.stored) }
        if (snapshots.any { it == null } ||
            frames.any { it.timeMs < 0L } ||
            (1 until frames.size).any { frames[it].timeMs <= frames[it - 1].timeMs } ||
            frames.indices.any {
                val s = snapshots[it] ?: return@any true
                BoostV5OfflineStateClockAudit.inspect(frames[it].timeMs, s).finding !=
                    BoostV5OfflineStateClockAudit.Finding.CONSISTENT
            }
        ) return Report(Finding.INVALID_SEQUENCE, emptyList())

        val suspicious = mutableListOf<Int>()
        val unknown = mutableListOf<Int>()
        for (i in 1 until frames.size) {
            val before = requireNotNull(snapshots[i - 1]).meal
            val after = requireNotNull(snapshots[i]).meal
            if (!before.committedInSession || after.committedInSession) continue
            val clock = before.lastCommitMs
            if (clock <= 0L || frames[i].timeMs - clock >= BoostV5MealStateDiagnostics.SESSION_LOCK_MIN_MS) continue
            val start = frames[i].nonPositiveDeltaContinuouslySinceMs
            val releaseSupported = start != null && start >= clock &&
                start <= frames[i].timeMs &&
                frames[i].timeMs - start >= BoostV5MealStateDiagnostics.NON_POSITIVE_RELEASE_MS
            if (!releaseSupported) {
                if (start == null) unknown += i else suspicious += i
            }
        }
        return when {
            suspicious.isNotEmpty() -> Report(Finding.EARLY_DROP_WITHOUT_EVIDENCE, suspicious)
            unknown.isNotEmpty() -> Report(Finding.EARLY_DROP_REQUIRES_REVIEW, unknown)
            snapshots.zipWithNext().none { (a, b) ->
                requireNotNull(a).meal.committedInSession && !requireNotNull(b).meal.committedInSession
            } -> Report(Finding.NO_LOCK_DROP, emptyList())
            else -> Report(Finding.LOCK_EXPIRED, emptyList())
        }
    }
}
