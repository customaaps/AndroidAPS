package app.aaps.plugins.boostport.telemetry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Read-only KMP extraction of the Boost V6 MealTimeLearner clock-history clustering.
 * Produces historic clock modes for inspection only: NO pre-meal target, APS
 * registration, dosing recommendations, persistence or pump interaction.
 *
 * Caller supplies a fixed historical UTC offset; DST-sensitive timestamps
 * require preprocessing by the caller. This does not process live sensor data.
 */
internal object BoostMealClockHistoryAudit {
    const val WINDOW_DAYS = 60L
    const val MIN_SESSIONS = 6
    const val MIN_DISTINCT_DAYS = 4
    const val CLUSTER_HALF_WIDTH_MIN = 45
    // Legacy expected history is roughly three meals/day over 60 days.
    // Bound quadratic neighbourhood clustering against oversized inputs.
    private const val MAX_INSPECT_EVENTS = 1_000
    private const val MINUTES_PER_DAY = 1440
    private const val DAY_MS = 86_400_000L
    private const val WINDOW_MS = WINDOW_DAYS * DAY_MS

    data class Mode(val centreMinute: Int, val eventCount: Int, val distinctDays: Int)

    private fun minuteOfDay(localMillis: Long): Int =
        MathFloor.floorMod(localMillis, DAY_MS).div(60_000L).toInt()

    private fun distance(a: Int, b: Int): Int {
        val diff = abs(a - b)
        return minOf(diff, MINUTES_PER_DAY - diff)
    }

    private object MathFloor {
        fun floorMod(n: Long, d: Long): Long = ((n % d) + d) % d
        fun floorDiv(n: Long, d: Long): Long {
            val q = n / d
            return if (n % d < 0L) q - 1 else q
        }
    }

    private fun circularMean(minutes: List<Int>): Int? {
        if (minutes.isEmpty()) return null
        val x = minutes.sumOf { cos(2.0 * PI * it / MINUTES_PER_DAY) }
        val y = minutes.sumOf { sin(2.0 * PI * it / MINUTES_PER_DAY) }
        if (abs(x) < 1e-10 && abs(y) < 1e-10) return null
        val angle = atan2(y, x)
        return ((angle * MINUTES_PER_DAY / (2.0 * PI)).roundToInt() %
            MINUTES_PER_DAY + MINUTES_PER_DAY) % MINUTES_PER_DAY
    }

    /**
     * Audit known historic meal-confirmation events only. Future timestamps,
     * duplicates and invalid offsets fail closed; no inferred meal event is created.
     */
    fun inspect(
        eventTimestampsMs: List<Long>,
        nowMs: Long,
        fixedUtcOffsetMs: Long,
        nightStartMinute: Int,
        nightEndMinute: Int,
    ): List<Mode> {
        if (nightStartMinute !in 0 until MINUTES_PER_DAY ||
            nightEndMinute !in 0 until MINUTES_PER_DAY ||
            fixedUtcOffsetMs !in -18L * 3_600_000L..18L * 3_600_000L ||
            eventTimestampsMs.size > MAX_INSPECT_EVENTS
        ) return emptyList()
        if (eventTimestampsMs.any { it > nowMs || it < 0L } ||
            eventTimestampsMs.size != eventTimestampsMs.distinct().size
        ) return emptyList()
        if (nowMs < WINDOW_MS || nowMs > Long.MAX_VALUE - 18L * 3_600_000L)
            return emptyList()

        fun inNight(minute: Int): Boolean = when {
            nightStartMinute == nightEndMinute -> false
            nightStartMinute < nightEndMinute ->
                minute in nightStartMinute until nightEndMinute
            else -> minute >= nightStartMinute || minute < nightEndMinute
        }

        val points = eventTimestampsMs.filter { it >= nowMs - WINDOW_MS }
            .map { timestamp ->
                val local = timestamp + fixedUtcOffsetMs
                minuteOfDay(local) to MathFloor.floorDiv(local, DAY_MS)
            }
            .filterNot { inNight(it.first) }.toMutableList()

        val modes = mutableListOf<Mode>()
        while (points.size >= MIN_SESSIONS) {
            val centre = points.maxByOrNull { candidate ->
                points.count { distance(candidate.first, it.first) <= CLUSTER_HALF_WIDTH_MIN }
            } ?: break
            val cluster = points.filter {
                distance(centre.first, it.first) <= CLUSTER_HALF_WIDTH_MIN
            }
            val distinct = cluster.map { it.second }.distinct().size
            if (cluster.size < MIN_SESSIONS || distinct < MIN_DISTINCT_DAYS) break
            val mean = circularMean(cluster.map { it.first }) ?: break
            modes += Mode(mean, cluster.size, distinct)
            points.removeAll(cluster.toSet())
        }
        return modes
    }
}
