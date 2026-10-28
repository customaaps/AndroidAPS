package app.aaps.plugins.boostport.telemetry

/**
 * Passive KMP validation boundary for prospective Health Connect reads.
 *
 * Shared by the explicit Android SDK reader and diagnostic callers. Callers must
 * check each record type's actual read permission before asking for data. This
 * class does not read Health Connect, write AAPS storage, or influence dosing.
 */
internal object HealthConnectReadPolicy {
    enum class Access { SDK_UNAVAILABLE, PERMISSION_REQUIRED, READABLE }

    fun access(sdkAvailable: Boolean, readPermissionGranted: Boolean): Access = when {
        !sdkAvailable -> Access.SDK_UNAVAILABLE
        !readPermissionGranted -> Access.PERMISSION_REQUIRED
        else -> Access.READABLE
    }

    data class HeartRateSample(
        val device: String,
        val timestampMs: Long,
        val beatsPerMinute: Double,
    )

    data class StepInterval(
        val dataOrigin: String,
        val startMs: Long,
        val endMs: Long,
        val stepCount: Long,
        val phoneDevice: Boolean = false,
    )

    /**
     * Return only in-window, finite HR samples for *diagnostic* inspection.
     * Minutes already covered by a live HR feed take precedence over HC backfill.
     * Keep at most one candidate per minute to prevent duplicate HC writers.
     */
    fun heartRateBackfillCandidates(
        records: List<HeartRateSample>,
        liveHrMinutes: Set<Long>,
        nowMs: Long,
        lookbackMs: Long,
    ): List<HeartRateSample> {
        if (nowMs < 0L || lookbackMs <= 0L) return emptyList()
        val sinceMs = if (nowMs >= lookbackMs) nowMs - lookbackMs else 0L
        val seen = liveHrMinutes.toMutableSet()
        return records.asSequence()
            .filter {
                it.timestampMs in sinceMs..nowMs &&
                    it.device.isNotBlank() &&
                    it.beatsPerMinute.isFinite() &&
                    it.beatsPerMinute > 0.0
            }
            .sortedWith(compareBy<HeartRateSample>({ it.timestampMs }, { it.device }))
            .filter { seen.add(it.timestampMs / 60_000L) }
            .toList()
    }

    /**
     * Retain distinct per-origin step intervals; never sum across overlapping
     * source applications. No records stays an empty list, not a fake zero.
     * Cross-midnight allocation and overlapping intervals require later adapters.
     */
    fun stepIntervals(
        records: List<StepInterval>,
        nowMs: Long,
        lookbackMs: Long,
    ): List<StepInterval> {
        if (nowMs < 0L || lookbackMs <= 0L) return emptyList()
        val sinceMs = if (nowMs >= lookbackMs) nowMs - lookbackMs else 0L
        return records.asSequence()
            .filter {
                it.dataOrigin.isNotBlank() &&
                    it.startMs >= sinceMs &&
                    it.endMs <= nowMs &&
                    it.startMs < it.endMs &&
                    it.stepCount >= 0L
            }
            .distinct()
            .sortedWith(compareBy<StepInterval>({ it.dataOrigin }, { it.startMs }, { it.endMs }))
            .toList()
    }
}

