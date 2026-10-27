package app.aaps.plugins.boostport.telemetry

/**
 * Passive KMP port of legacy WearStepSource step reconstruction.
 * Diagnostic snapshots only: no database, APS, or pump integration.
 */
internal object WearStepDiagnostics {
    const val FRESH_MS = 12 * 60_000L
    const val RESOLVE_GRACE_MS = 30 * 60_000L
    private const val FIVE_MIN_MS = 300_000L
    private const val DAY_MS = 86_400_000L

    data class Reading(val timestampMs: Long, val steps5Min: Int)
    data class DailyTotal(val dayIndex: Long, val steps: Int, val source: String = "wear")

    fun isFresh(readings: List<Reading>, nowMs: Long): Boolean =
        mostRecentAge(readings, nowMs)?.let { it <= FRESH_MS } ?: false

    fun isRecentlyFresh(readings: List<Reading>, nowMs: Long): Boolean =
        mostRecentAge(readings, nowMs)?.let { it <= RESOLVE_GRACE_MS } ?: false

    // Future-dated samples never provide evidence of a working feed.
    private fun mostRecentAge(readings: List<Reading>, nowMs: Long): Long? {
        if (nowMs < 0L) return null
        val newest = readings.asSequence().map { it.timestampMs }
            .filter { it in 0L..nowMs }.maxOrNull() ?: return null
        return nowMs - newest
    }

    /**
     * Approximate per-day count, one maximum per non-overlapping 5-minute slot.
     * A zero cannot be interpreted as inactivity unless isFresh is true.
     */
    fun stepsToday(readings: List<Reading>, dayStartMs: Long, nowMs: Long): Int {
        if (dayStartMs < 0L || nowMs < dayStartMs) return 0
        val slots = mutableMapOf<Long, Int>()
        for (reading in readings) {
            if (reading.timestampMs !in dayStartMs..nowMs || reading.steps5Min < 0) continue
            val slot = reading.timestampMs / FIVE_MIN_MS
            slots[slot] = maxOf(slots[slot] ?: 0, reading.steps5Min)
        }
        return sumBounded(slots.values)
    }

    /**
     * Exclude today's partial local day as the legacy WearStepSource does.
     * A future Android caller must supply a correct offset; DST needs device checks.
     */
    fun completedDailyTotals(readings: List<Reading>, todayIndex: Long, localOffsetMs: Long): List<DailyTotal> {
        val perDay = mutableMapOf<Long, MutableMap<Long, Int>>()
        for (reading in readings) {
            if (reading.timestampMs < 0L || reading.steps5Min < 0) continue
            val localTime = reading.timestampMs + localOffsetMs
            if ((localOffsetMs > 0 && localTime < reading.timestampMs) ||
                (localOffsetMs < 0 && localTime > reading.timestampMs) || localTime < 0L) continue
            val day = localTime / DAY_MS
            if (day >= todayIndex) continue
            val slot = reading.timestampMs / FIVE_MIN_MS
            val slots = perDay.getOrPut(day) { mutableMapOf() }
            slots[slot] = maxOf(slots[slot] ?: 0, reading.steps5Min)
        }
        return perDay.map { (day, slots) -> DailyTotal(day, sumBounded(slots.values)) }
            .sortedBy { it.dayIndex }
    }

    private fun sumBounded(counts: Collection<Int>): Int {
        var total = 0L
        for (count in counts) {
            total += count.toLong()
            if (total >= Int.MAX_VALUE) return Int.MAX_VALUE
        }
        return total.toInt()
    }
}
