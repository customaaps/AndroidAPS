package app.aaps.plugins.boostport.telemetry

/**
 * Passive KMP adaptation of legacy Boost V1 NightWindow's clock fact.
 *
 * Night-clock membership is independent of the Boost night-mode *policy*
 * switch. It is data for a future offline comparison, NOT a profile,
 * insulin, SMB, basal, target or pump action. No runtime clock is read.
 *
 * The source uses absolute local-midnight-relative start/end timestamps.
 * Equality is an EMPTY window rather than an all-day window. A wrapping
 * window contains [start, midnight+24h) union [midnight, end).
 */
internal object NightClockWindowDiagnostics {
    private const val DAY_MS = 86_400_000L

    /**
     * Null means an inconsistent/corrupt time basis or a risk of overflow.
     * The false result is a valid, evaluated NOT-IN-WINDOW outcome.
     */
    fun inspect(nowMs: Long, startMs: Long, endMs: Long): Boolean? {
        if (nowMs < 0L || startMs < 0L || endMs < 0L) return null
        if (startMs == endMs) return false
        if (endMs > startMs) return nowMs >= startMs && nowMs < endMs

        // Same local-midnight basis: the source compares both relative to
        // this midnight. Refuse arbitrary multi-day reversed time ranges.
        if (startMs - endMs > DAY_MS) return null
        if (startMs < DAY_MS || endMs > Long.MAX_VALUE - DAY_MS) return null
        val previousDayStart = startMs - DAY_MS
        val nextDayEnd = endMs + DAY_MS
        return (nowMs >= previousDayStart && nowMs < endMs) ||
            (nowMs >= startMs && nowMs < nextDayEnd)
    }

    /**
     * Convert a caller-supplied local-midnight anchor and schedule minutes
     * into the same absolute time coordinates used by NightWindow.
     */
    fun inspectLocal(
        nowMs: Long,
        localMidnightMs: Long,
        startMinute: Int,
        endMinute: Int,
    ): Boolean? {
        if (localMidnightMs < 0L || nowMs < 0L ||
            startMinute !in 0..1439 || endMinute !in 0..1439 ||
            localMidnightMs > Long.MAX_VALUE - DAY_MS
        ) return null
        val start = localMidnightMs + startMinute * 60_000L
        val end = localMidnightMs + endMinute * 60_000L
        return inspect(nowMs, start, end)
    }
}
