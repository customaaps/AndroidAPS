package app.aaps.plugins.boostport.telemetry

import kotlin.math.abs
import kotlin.math.min

/**
 * Passive KMP adaptation of Boost V6 MealTimeLearner's clustering rules.
 *
 * Accepts explicit, already-confirmed meal timestamps for offline diagnostics.
 * There is no meal detector, scheduling, persistence, prediction service,
 * glucose target adjustment, or connection to APS/pump/dosing.
 *
 * A candidate pattern requires >= 6 events spanning >= 4 distinct local days.
 * Nighttime events and out-of-window/future records are excluded before
 * clustering. This class does not implement the live pre-meal target action.
 */
internal object MealTimeDiagnostics {
    const val WINDOW_DAYS = 60L
    const val MIN_SESSIONS = 6
    const val MIN_DISTINCT_DAYS = 4
    const val CLUSTER_HALF_WIDTH_MIN = 45

    private const val DAY_MS = 86_400_000L
    private const val WINDOW_MS = WINDOW_DAYS * DAY_MS
    private const val MINUTES_PER_DAY = 1_440
    private const val MAX_EVENTS = 500

    data class MealMode(
        val centreMin: Int,
        val eventCount: Int,
        val distinctDays: Int,
    )

    private data class Point(val minute: Int, val localDay: Long)

    /** Legacy wrap-aware night gate. End is exclusive; start == end is empty. */
    fun inNightMinutes(minute: Int, start: Int, end: Int): Boolean {
        if (minute !in 0 until MINUTES_PER_DAY ||
            start !in 0 until MINUTES_PER_DAY ||
            end !in 0 until MINUTES_PER_DAY || start == end
        ) return false
        return if (start < end) minute in start until end
        else minute >= start || minute < end
    }

    /**
     * The supplied events MUST be confirmed meal commits, not CGM predictions.
     * Caller supplies nowMs and the local UTC offset, with explicit night hours.
     * Fail closed when the night schedule is malformed or input overwhelms the
     * offline inspection budget. No source database is changed.
     */
    fun modes(
        confirmedMealTimestampsMs: List<Long>,
        nowMs: Long,
        localOffsetMs: Long,
        nightStartMin: Int,
        nightEndMin: Int,
    ): List<MealMode> {
        if (nowMs < 0L ||
            localOffsetMs !in -18L * 3_600_000L..18L * 3_600_000L ||
            nightStartMin !in 0 until MINUTES_PER_DAY ||
            nightEndMin !in 0 until MINUTES_PER_DAY ||
            confirmedMealTimestampsMs.size > MAX_EVENTS
        ) return emptyList()

        val since = (nowMs - WINDOW_MS).coerceAtLeast(0L)
        val points = confirmedMealTimestampsMs.asSequence()
            .filter { it in since..nowMs }
            .distinct()
            .mapNotNull { timestamp ->
                // Avoid overflow for extreme timestamps before local-time math.
                if ((localOffsetMs > 0 && timestamp > Long.MAX_VALUE - localOffsetMs) ||
                    (localOffsetMs < 0 && timestamp < -localOffsetMs)
                ) return@mapNotNull null
                val localMs = timestamp + localOffsetMs
                if (localMs < 0L) return@mapNotNull null
                val minute = ((localMs % DAY_MS) / 60_000L).toInt()
                if (inNightMinutes(minute, nightStartMin, nightEndMin)) null
                else Point(minute, localMs / DAY_MS)
            }
            .toMutableList()

        if (points.size < MIN_SESSIONS) return emptyList()
        val modes = mutableListOf<MealMode>()
        while (points.size >= MIN_SESSIONS) {
            // Greediest 45-minute circular neighbourhood, as in legacy V6.
            val anchor = points.maxByOrNull { center ->
                points.count { circularDistance(it.minute, center.minute) <= CLUSTER_HALF_WIDTH_MIN }
            } ?: break
            val cluster = points.filter {
                circularDistance(it.minute, anchor.minute) <= CLUSTER_HALF_WIDTH_MIN
            }
            val days = cluster.map { it.localDay }.distinct().size
            if (cluster.size < MIN_SESSIONS || days < MIN_DISTINCT_DAYS) break
            val centre = SleepHistoryDiagnostics.circularMean(cluster.map { it.minute }) ?: break
            modes += MealMode(centre, cluster.size, days)
            points.removeAll(cluster.toSet())
        }
        return modes.sortedWith(
            compareByDescending<MealMode> { it.eventCount }
                .thenByDescending { it.distinctDays }
                .thenBy { it.centreMin }
        )
    }

    private fun circularDistance(a: Int, b: Int): Int {
        val difference = abs(a - b)
        return min(difference, MINUTES_PER_DAY - difference)
    }
}
