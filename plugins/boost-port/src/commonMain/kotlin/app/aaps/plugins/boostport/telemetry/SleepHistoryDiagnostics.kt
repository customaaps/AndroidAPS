package app.aaps.plugins.boostport.telemetry

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Read-only KMP adaptation of V1 SleepHistoryTracker aggregation.
 *
 * No persistence, clock subscriptions, target changes, sleep-state activation,
 * APS interaction or pump access. An offline audit supplies closed sessions.
 *
 * A scheduled "boundary" wake is NOT evidence of an actual wake: only genuine
 * hr_steps/resume/steps events can train the observed wake-time diagnostic.
 * Insufficient, malformed, or ambiguous data yields null, not a new target.
 */
internal object SleepHistoryDiagnostics {
    const val WINDOW_DAYS = 28L
    const val MIN_SESSIONS = 7
    private const val DAY_MS = 86_400_000L
    private const val MINUTE_MS = 60_000L
    private const val MAX_SESSION_MS = DAY_MS

    data class Session(
        val sleepStartMs: Long,
        val wakeMs: Long,
        val wakeReason: String? = null,
        val sleepHrP10: Int? = null,
        val daytimeHrP10: Int? = null,
    )

    data class Summary(
        val validSessions: Int,
        val genuineWakes: Int,
        val onsetMinute: Int?,
        val wakeMinute: Int?,
        val meanDurationMinutes: Int?,
        val restingHrBpm: Int?,
        val daytimeHrBpm: Int?,
    )

    /**
     * No diagnosis or automatic action: this is only a quality-controlled
     * aggregate of already-closed source sessions.
     */
    fun summarize(
        sessions: List<Session>,
        nowMs: Long,
        localOffsetMs: Long,
    ): Summary {
        val empty = Summary(0, 0, null, null, null, null, null)
        if (nowMs < 0L || localOffsetMs !in -18L * 3_600_000L..18L * 3_600_000L)
            return empty
        val cutoff = (nowMs - WINDOW_DAYS * DAY_MS).coerceAtLeast(0L)
        val valid = sessions.filter { s ->
            s.sleepStartMs in cutoff..nowMs &&
                s.wakeMs in s.sleepStartMs..nowMs &&
                s.wakeMs > s.sleepStartMs &&
                s.wakeMs - s.sleepStartMs <= MAX_SESSION_MS
        }
        val genuine = valid.filter { it.wakeReason in setOf("hr_steps", "resume", "steps") }
        val enoughSessions = valid.size >= MIN_SESSIONS
        val sleepHr = valid.mapNotNull { it.sleepHrP10?.takeIf { value -> value > 0 } }
        val daytimeHr = valid.mapNotNull { it.daytimeHrP10?.takeIf { value -> value > 0 } }
        return Summary(
            validSessions = valid.size,
            genuineWakes = genuine.size,
            onsetMinute = if (enoughSessions) circularMean(valid.map {
                minuteOfDay(it.sleepStartMs, localOffsetMs)
            }) else null,
            wakeMinute = if (genuine.size >= MIN_SESSIONS) circularMean(genuine.map {
                minuteOfDay(it.wakeMs, localOffsetMs)
            }) else null,
            meanDurationMinutes = if (enoughSessions) {
                (valid.sumOf { (it.wakeMs - it.sleepStartMs) / MINUTE_MS } / valid.size).toInt()
            } else null,
            restingHrBpm = if (sleepHr.size >= MIN_SESSIONS) median(sleepHr) else null,
            daytimeHrBpm = if (daytimeHr.size >= MIN_SESSIONS) median(daytimeHr) else null,
        )
    }

    /** 10th percentile for read-only HR quality summaries, minimum 30 samples. */
    fun p10(readings: List<Double>, minSamples: Int = 30): Int? {
        if (minSamples < 1) return null
        val valid = readings.filter { it.isFinite() && it > 0.0 }.sorted()
        if (valid.size < minSamples) return null
        val index = ((valid.size - 1) * 0.10).toInt()
        return valid[index].toInt()
    }

    private fun median(values: List<Int>): Int = values.sorted()[values.size / 2]

    /** Modulo first prevents overflow when source timestamps approach Long.MAX_VALUE. */
    fun minuteOfDay(utcMs: Long, offsetMs: Long): Int {
        val remainder = ((utcMs % DAY_MS + offsetMs % DAY_MS) % DAY_MS + DAY_MS) % DAY_MS
        return (remainder / MINUTE_MS).toInt()
    }

    /**
     * Circular average: 23:50 and 00:10 are midnight, not midday.
     * An antipodal or directionless distribution is not a learnable time.
     */
    fun circularMean(minutes: List<Int>): Int? {
        if (minutes.isEmpty() || minutes.any { it !in 0..1439 }) return null
        var x = 0.0
        var y = 0.0
        for (minute in minutes) {
            val angle = 2.0 * PI * minute / 1440.0
            x += cos(angle)
            y += sin(angle)
        }
        if (x * x + y * y < 1e-12) return null
        val radians = (atan2(y, x) + 2.0 * PI) % (2.0 * PI)
        return (radians * 1440.0 / (2.0 * PI)).roundToInt() % 1440
    }
}
