package app.aaps.plugins.boostport.telemetry

/**
 * Passive KMP inspection of the polling policy in legacy HealthConnectHrIngest.
 *
 * The original ingester re-reads an 18-hour lookback on each eligible poll
 * instead of advancing a high-water mark: Garmin can upload a morning batch
 * with overnight timestamps that are older than the last poll.
 *
 * No HealthConnectClient, coroutine, Android permission API, Preferences,
 * database, live APS cycle or pump connection exists here. A WOULD_SWEEP
 * result is diagnostic only and must never trigger a real read or write.
 */
internal object BoostHcHrPollDiagnostics {
    const val LOOKBACK_MS = 18L * 60L * 60_000L
    private const val MINUTE_MS = 60_000L
    private const val MAX_POLL_MIN = 24 * 60

    enum class Gate {
        DISABLED, SDK_UNAVAILABLE, PERMISSION_REQUIRED, IN_FLIGHT,
        THROTTLED, INVALID_INPUT, WOULD_SWEEP,
    }

    data class State(
        val lastPollStartedMs: Long = 0L,
        val inFlight: Boolean = false,
    )

    data class Decision(
        val gate: Gate,
        val requestedSinceMs: Long? = null,
        val requestedUntilMs: Long? = null,
        val nextState: State,
    )

    /**
     * Compute a hypothetical read window for a caller-supplied moment.
     * The legacy throttle is based on poll *start* (not sync completion);
     * inflight calls never issue concurrent work.
     *
     * We explicitly require permission as a fail-closed diagnostic boundary:
     * the Android implementation itself checks permission inside syncOnce.
     */
    fun inspect(
        previous: State,
        nowMs: Long,
        enabled: Boolean,
        sdkAvailable: Boolean,
        readHeartRateGranted: Boolean,
        pollIntervalMinutes: Int,
    ): Decision {
        fun reject(gate: Gate) = Decision(gate, nextState = previous)
        if (nowMs <= 0L || previous.lastPollStartedMs < 0L ||
            previous.lastPollStartedMs > nowMs || pollIntervalMinutes < 0 ||
            pollIntervalMinutes > MAX_POLL_MIN
        ) return reject(Gate.INVALID_INPUT)
        if (!enabled) return reject(Gate.DISABLED)
        if (!sdkAvailable) return reject(Gate.SDK_UNAVAILABLE)
        if (!readHeartRateGranted) return reject(Gate.PERMISSION_REQUIRED)
        if (previous.inFlight) return reject(Gate.IN_FLIGHT)

        val intervalMs = pollIntervalMinutes.coerceAtLeast(1).toLong() * MINUTE_MS
        if (nowMs - previous.lastPollStartedMs < intervalMs)
            return reject(Gate.THROTTLED)
        val since = (nowMs - LOOKBACK_MS).coerceAtLeast(0L)
        return Decision(
            gate = Gate.WOULD_SWEEP,
            requestedSinceMs = since,
            requestedUntilMs = nowMs,
            nextState = State(lastPollStartedMs = nowMs, inFlight = true),
        )
    }

    /**
     * Represent the completion of a hypothetical read. A failed read still
     * retains the start timestamp, matching the source's throttle behavior.
     * Calling this does NOT indicate any data was actually read or saved.
     */
    fun finish(previous: State): State =
        if (previous.inFlight) previous.copy(inFlight = false) else previous
}
