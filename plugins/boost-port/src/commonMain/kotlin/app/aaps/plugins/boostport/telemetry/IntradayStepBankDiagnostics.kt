package app.aaps.plugins.boostport.telemetry

/**
 * Immutable, passive KMP port of DailyStepHistoryTracker.IntradayStepBank.
 *
 * The source's 2026-07-07 rollover fix retains the largest observed
 * per-source step total throughout a local day. A watch's sensor counter
 * can reset at device midnight BEFORE the phone's local midnight; its peak
 * must remain available when the completed day is recorded.
 *
 * Pure diagnostic data: no Android sensor reads, background jobs, JSON,
 * persistence, ISF application, AAPS algorithm or pump integration.
 */
internal object IntradayStepBankDiagnostics {
    const val WINDOW_DAYS = 28L
    private const val MAX_SOURCES = 32

    data class Bank(
        val localDayIndex: Long = -1L,
        val maximumBySource: Map<String, Int> = emptyMap(),
    )

    data class CompletedDay(
        val dayIndex: Long,
        val steps: Int,
        val source: String,
    )

    data class Cycle(
        val bank: Bank,
        val closedDayTotals: List<CompletedDay>,
    )

    /**
     * Explicit canonicalisation, faithful to Boost's step resolver IDs,
     * but rejects empty/ambiguous source names instead of fabricating hc:.
     */
    fun canonical(source: String): String? {
        if (source.isBlank() || source.length > 150) return null
        val name = source.trim()
        return when {
            name == "wear" || name == "phone" -> name
            name == "garmin" || name == "com.garmin.android.apps.connectmobile" -> "garmin"
            name.startsWith("hc:") && name.length > 3 -> name
            name.contains('.') && name.substringAfterLast('.').isNotBlank() ->
                "hc:" + name.substringAfterLast('.')
            else -> null
        }
    }

    /**
     * One clock-controlled step, with no actual wall-clock lookup. Invalid
     * or backwards rollover clocks fail closed with null, not a forged day.
     * An empty or zero source event never erases a previously seen peak.
     *
     * At day rollover only the previous completed day is emitted, if it
     * lies within the last 28 days. Future/stale banks are discarded.
     * The caller may choose to persist the returned immutable Bank elsewhere.
     */
    fun advance(
        previous: Bank,
        todayIndex: Long,
        currentCounts: Map<String, Int>,
    ): Cycle? {
        if (todayIndex < 0L || previous.localDayIndex < -1L ||
            previous.maximumBySource.size > MAX_SOURCES ||
            currentCounts.size > MAX_SOURCES ||
            currentCounts.values.any { it < 0 } ||
            previous.maximumBySource.any { (k, v) ->
                canonical(k) == null || canonical(k) != k || v < 0
            }
        ) return null

        val normalized = linkedMapOf<String, Int>()
        for ((raw, count) in currentCounts) {
            val source = canonical(raw) ?: return null
            normalized[source] = maxOf(normalized[source] ?: 0, count)
        }
        val cutoff = (todayIndex - WINDOW_DAYS).coerceAtLeast(0L)
        val completed = if (previous.localDayIndex != todayIndex &&
            previous.localDayIndex in cutoff until todayIndex
        ) previous.maximumBySource
            .filterValues { it > 0 }
            .map { (src, count) -> CompletedDay(previous.localDayIndex, count, src) }
        else emptyList()

        val next = if (previous.localDayIndex == todayIndex)
            previous.maximumBySource.toMutableMap() else linkedMapOf()
        for ((source, count) in normalized) {
            if (count > (next[source] ?: 0)) next[source] = count
        }
        if (next.size > MAX_SOURCES) return null
        return Cycle(
            bank = Bank(todayIndex, next.toMap()),
            closedDayTotals = completed,
        )
    }
}
