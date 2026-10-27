package app.aaps.plugins.boostport.telemetry

/**
 * Passive KMP port of DailyStepHistoryTracker's completed-day baseline,
 * 24–48 hour activity-load and same-day pace comparisons (Boost V1/V6).
 *
 * All percentages below are historical, HYPOTHETICAL ISF comparisons,
 * never applied to a profile, algorithm, pump, bolus or temp basal. No
 * Health Connect, storage, clock, background work or AAPS integration.
 */
internal object DailyStepActivityDiagnostics {
    const val WINDOW_DAYS = 28L
    const val MIN_BASELINE_DAYS = 7
    const val ACTIVITY_MAX_ISF_PCT = 15.0
    const val INACTIVITY_MAX_ISF_PCT = 8.0
    const val ACTIVITY_FULL_RATIO = 2.0
    const val INACTIVITY_FULL_RATIO = 0.4
    private const val DAY_MS = 86_400_000L
    private const val MAX_ROWS = 500

    // Original 2026-06-19 generic diurnal curve, indexed by local hour.
    private val DIURNAL = listOf(
        0.00, 0.00, 0.00, 0.00, 0.00, 0.01, 0.03, 0.07,
        0.13, 0.20, 0.28, 0.36, 0.44, 0.52, 0.59, 0.66,
        0.73, 0.80, 0.86, 0.91, 0.95, 0.98, 0.99, 1.00,
    )

    data class DailyTotal(val dayIndex: Long, val steps: Int, val source: String)
    data class History(val days: Map<Long, DailyTotal> = emptyMap())
    data class Shadow(
        val baselineSteps: Int?,
        val yesterdaySteps: Int?,
        val activityRatio: Double?,
        val wouldDeltaIsfPct: Double,
        val reason: String,
    )
    data class Intraday(
        val ratio: Double?,
        val expectedByNow: Int,
        val wouldDeltaIsfPct: Double,
    )

    /** UTC to local epoch-day index; reject overflow and implausible offset. */
    fun dayIndex(utcMs: Long, localOffsetMs: Long): Long? {
        if (utcMs < 0L || localOffsetMs !in
            (-18L * 3_600_000L)..(18L * 3_600_000L) ||
            (localOffsetMs > 0L && utcMs > Long.MAX_VALUE - localOffsetMs)
        ) return null
        val local = utcMs + localOffsetMs
        val quotient = local / DAY_MS
        return if (local < 0L && local % DAY_MS != 0L) quotient - 1L else quotient
    }

    /**
     * Merge only completed days in [todayIndex-28, todayIndex).
     * As in legacy 2026-07-03 HOLD-HIGHER, a later smaller estimate must
     * never overwrite a larger day total, including on repeated imports.
     * Reject corrupt rows rather than interpreting negative steps as rest.
     */
    fun merge(history: History, updates: List<DailyTotal>, todayIndex: Long): History? {
        if (todayIndex < 0L || updates.size > MAX_ROWS ||
            history.days.size > MAX_ROWS ||
            history.days.any { (day, row) ->
                day != row.dayIndex || day < 0L || row.steps < 0 || row.source.isBlank()
            } ||
            updates.any { it.dayIndex < 0L || it.steps < 0 || it.source.isBlank() }
        ) return null
        val firstDay = (todayIndex - WINDOW_DAYS).coerceAtLeast(0L)
        val next = history.days.filterKeys { it in firstDay until todayIndex }.toMutableMap()
        for (row in updates) {
            if (row.dayIndex !in firstDay until todayIndex) continue
            val previous = next[row.dayIndex]
            if (previous == null || row.steps > previous.steps) next[row.dayIndex] = row
        }
        return History(next.toMap())
    }

    /** Median of completed days, excluding the most recent two from baseline. */
    fun baseline(history: History, todayIndex: Long, excludeRecent: Int = 2): Int? {
        if (todayIndex < 0L || excludeRecent !in 0..WINDOW_DAYS.toInt() ||
            history.days.size > MAX_ROWS
        ) return null
        val firstDay = (todayIndex - WINDOW_DAYS).coerceAtLeast(0L)
        val exclusive = (todayIndex - excludeRecent).coerceAtLeast(0L)
        val completed = history.days.values
            .filter { it.dayIndex in firstDay until exclusive &&
                it.steps >= 0 && it.source.isNotBlank() }
            .map { it.steps }.sorted()
        return if (completed.size < MIN_BASELINE_DAYS) null
        else completed[completed.size / 2]
    }

    /**
     * Previous-day weight 1.0, day-before weight 0.5, from the legacy
     * shadow. Missing history returns neutral; insufficient history is
     * never interpreted as lower activity.
     */
    fun shadow(history: History, todayIndex: Long): Shadow {
        val base = baseline(history, todayIndex)
        val yesterday = history.days[todayIndex - 1L]?.steps
        if (base == null || base <= 0 || yesterday == null || yesterday < 0) {
            return Shadow(base, yesterday, null, 0.0, "insufficient-history")
        }
        val before = history.days[todayIndex - 2L]?.steps
            ?.takeIf { it >= 0 } ?: base
        val weighted = (yesterday + 0.5 * before) / 1.5
        val ratio = weighted / base
        val factor = if (ratio >= 1.0) {
            ((ratio - 1.0) / (ACTIVITY_FULL_RATIO - 1.0))
                .coerceIn(0.0, 1.0) * ACTIVITY_MAX_ISF_PCT
        } else {
            -((1.0 - ratio) / (1.0 - INACTIVITY_FULL_RATIO))
                .coerceIn(0.0, 1.0) * INACTIVITY_MAX_ISF_PCT
        }
        return Shadow(base, yesterday, ratio, factor,
            if (ratio >= 1.0) "activity-load" else "inactivity")
    }

    /** Same-day pace comparison is raise-only in the original shadow. */
    fun intraday(stepsToday: Int, baselineSteps: Int?, localHour: Int): Intraday? {
        if (stepsToday < 0 || localHour !in 0..23 ||
            (baselineSteps != null && baselineSteps < 0)
        ) return null
        if (baselineSteps == null || baselineSteps == 0) return Intraday(null, 0, 0.0)
        val expected = baselineSteps.toDouble() * DIURNAL[localHour].coerceAtLeast(0.02)
        val ratio = stepsToday / expected
        val factor = ((ratio - 1.0) / (ACTIVITY_FULL_RATIO - 1.0))
            .coerceIn(0.0, 1.0) * ACTIVITY_MAX_ISF_PCT
        return Intraday(ratio, expected.toInt(), factor)
    }
}
