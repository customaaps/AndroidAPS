package app.aaps.plugins.boostport.telemetry

/**
 * Offline, immutable KMP adaptation of StepService's 5-minute step buckets.
 *
 * Caller supplies TYPE_STEP_COUNTER observations, which are cumulative since
 * reboot. The first reading is a baseline. Negative deltas (reboot) and
 * increments >1500 (sensor glitch) are NOT credited to a bucket.
 *
 * The original StepService window selection excludes the current bucket and
 * uses strict (> cutoff, < current) bounds. The 10, 15, 30 and 60 minute
 * helper calls therefore select 2, 3, 5 and 11 completed buckets; this
 * diagnostic preserves the legacy selection for parity auditing.
 *
 * Unknown feed is reported as null, not as measured inactivity. No Sensor,
 * callbacks, APS/ISF/SMB, storage or pump interface is used.
 */
internal object StepBucketWindowDiagnostics {
    const val BUCKET_MS = 300_000L
    const val MAX_PLAUSIBLE_INCREMENT = 1500
    const val HISTORY_BUCKETS = 20

    data class State(
        val previousRawCount: Long? = null,
        val lastEventMs: Long? = null,
        val totalsByBucket: Map<Long, Int> = emptyMap(),
    )

    data class Window(
        val steps: Int?,
        val feedKnown: Boolean,
        val bucketCount: Int,
    )

    fun accept(
        before: State,
        cumulativeSteps: Long,
        receivedAtMs: Long,
    ): State? {
        if (cumulativeSteps < 0L || receivedAtMs < 0L ||
            before.previousRawCount?.let { it < 0L } == true ||
            before.lastEventMs?.let { it < 0L || it > receivedAtMs } == true ||
            before.totalsByBucket.size > HISTORY_BUCKETS + 1 ||
            before.totalsByBucket.any { (key, value) -> key < 0L || value < 0 }
        ) return null

        val bucket = receivedAtMs / BUCKET_MS
        val values = before.totalsByBucket.toMutableMap()
        val previous = before.previousRawCount
        if (previous != null && cumulativeSteps >= previous) {
            val delta = cumulativeSteps - previous
            if (delta in 0L..MAX_PLAUSIBLE_INCREMENT.toLong()) {
                val total = values[bucket]?.toLong() ?: 0L
                values[bucket] = (total + delta).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
        }
        val oldest = (bucket - HISTORY_BUCKETS).coerceAtLeast(0L)
        val retained = values.filterKeys { it >= oldest }.toMap()
        return State(cumulativeSteps, receivedAtMs, retained)
    }

    /**
     * Source-compatible bucket window selection, without reading a live
     * clock. Missing sensor data remains unknown, never 'zero steps'.
     */
    fun completedWindow(
        state: State,
        nowMs: Long,
        windowMinutes: Int,
    ): Window? {
        if (nowMs < 0L || windowMinutes !in listOf(5, 10, 15, 30, 60) ||
            state.previousRawCount?.let { it < 0L } == true ||
            state.lastEventMs?.let { it < 0L || it > nowMs } == true ||
            state.totalsByBucket.size > HISTORY_BUCKETS + 1 ||
            state.totalsByBucket.any { (key, value) -> key < 0L || value < 0 }
        ) return null
        val originalCount = when (windowMinutes) {
            5 -> 2
            10 -> 3
            15 -> 4
            30 -> 6
            else -> 12
        }
        val currentBucket = nowMs / BUCKET_MS
        val cutoff = currentBucket - originalCount
        val completed = state.totalsByBucket
            .filterKeys { it > cutoff && it < currentBucket }
        val count = completed.values.fold(0L) { acc, value ->
            (acc + value).coerceAtMost(Int.MAX_VALUE.toLong())
        }
        return Window(
            steps = if (state.previousRawCount == null) null else count.toInt(),
            feedKnown = state.previousRawCount != null,
            bucketCount = completed.size,
        )
    }
}
