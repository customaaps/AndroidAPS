package app.aaps.plugins.boostport.telemetry

/**
 * Read-only counter-to-snapshot mapper used by a future Android sensor adapter.
 *
 * The Android TYPE_STEP_COUNTER value is cumulative since device boot. We must
 * not interpret a missing callback as zero steps, or trust an implausibly large
 * jump after a reset. The accumulated value below is diagnostic only: it is NOT
 * today's steps and is never passed to a dosing engine.
 *
 * This type has no Android, Health Connect, persistence, or APS dependencies.
 */
internal class StepCounterDiagnostics(
    private val staleAfterMs: Long = 12 * 60_000L,
    private val maxPlausibleIncrement: Long = 1_500L,
) {
    init {
        require(staleAfterMs > 0L)
        require(maxPlausibleIncrement > 0L)
    }

    data class Snapshot(
        val observedIncrementTotal: Long,
        val feedLive: Boolean,
        val lastEventMs: Long?,
        val discontinuities: Int,
    )

    private var previousRaw: Long? = null
    private var lastEventMs: Long? = null
    private var observedIncrementTotal: Long = 0L
    private var discontinuities: Int = 0

    /**
     * Called only when a genuine step-counter sensor event is received.
     * Invalid or time-reversed data never updates the last-seen timestamp.
     */
    fun accept(rawCount: Long, receivedAtMs: Long): Boolean {
        if (rawCount < 0L || receivedAtMs < 0L) return false
        val previousTime = lastEventMs
        if (previousTime != null && receivedAtMs < previousTime) return false

        val previous = previousRaw
        if (previous != null) {
            if (rawCount < previous) {
                // Reboot / sensor reset: establish a new baseline, not a step spike.
                discontinuities += 1
            } else {
                val increment = rawCount - previous
                if (increment > maxPlausibleIncrement) {
                    // Long callback gap or bogus jump: never fabricate a count.
                    discontinuities += 1
                } else {
                    observedIncrementTotal =
                        if (observedIncrementTotal > Long.MAX_VALUE - increment) Long.MAX_VALUE
                        else observedIncrementTotal + increment
                }
            }
        }
        previousRaw = rawCount
        lastEventMs = receivedAtMs
        return true
    }

    fun snapshot(nowMs: Long): Snapshot {
        val ageMs = lastEventMs?.let { nowMs - it }
        return Snapshot(
            observedIncrementTotal = observedIncrementTotal,
            feedLive = ageMs != null && ageMs in 0L..staleAfterMs,
            lastEventMs = lastEventMs,
            discontinuities = discontinuities,
        )
    }
}
