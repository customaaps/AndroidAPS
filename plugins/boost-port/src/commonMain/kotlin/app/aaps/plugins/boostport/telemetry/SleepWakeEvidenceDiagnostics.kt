package app.aaps.plugins.boostport.telemetry

import kotlin.math.roundToInt

/**
 * Read-only KMP extraction of SleepStateDetector's wake-evidence arithmetic.
 *
 * The source keeps one anchor before the 60-minute step lookback and sums
 * positive changes to tolerate lumped Wear uploads. This computes evidence
 * only; it does NOT set sleep state, wake the user, change targets, suppress
 * SMB, update ISF or connect to any AAPS/pump interface.
 */
internal object SleepWakeEvidenceDiagnostics {
    const val STEP_LOOKBACK_MIN = 60
    const val STEP_WITH_HR_MIN = 100
    const val STRONG_STEP_MIN = 250
    const val TRANSMISSION_RESUME_STEP_MIN = 20
    const val MIN_FRESH_HR_SAMPLES = 3
    const val HR_SUSTAIN_MIN = 10.0
    private const val MAX_SAMPLES = 100
    private const val MINUTE_MS = 60_000L

    data class StepSample(val receivedAtMs: Long, val stepsToday: Int)
    data class History(val samples: List<StepSample> = emptyList())
    data class Evidence(
        val growthSteps: Int,
        val clearsHrAssistedStepThreshold: Boolean,
        val clearsStrongStepThreshold: Boolean,
    )

    private fun valid(history: History): Boolean =
        history.samples.size <= MAX_SAMPLES &&
            history.samples.all { it.receivedAtMs > 0L && it.stepsToday >= 0 } &&
            history.samples.zipWithNext().all { (a, b) ->
                a.receivedAtMs < b.receivedAtMs
            }

    /**
     * Pure addition of a timestamped cumulative sample; retain an anchor
     * immediately before the lookback cutoff, matching the original
     * SleepStateDetector's running sample list.
     */
    fun record(
        previous: History,
        nowMs: Long,
        stepsToday: Int?,
    ): History? {
        if (!valid(previous) || nowMs <= 0L ||
            previous.samples.lastOrNull()?.receivedAtMs?.let { nowMs < it } == true
        ) return null
        if (stepsToday == null) return previous
        if (stepsToday < 0) return null

        val last = previous.samples.lastOrNull()
        val next = if (last?.receivedAtMs == nowMs)
            previous.samples.dropLast(1) + StepSample(nowMs, stepsToday)
        else previous.samples + StepSample(nowMs, stepsToday)

        val cutoff = nowMs - STEP_LOOKBACK_MIN * MINUTE_MS
        var first = 0
        while (first + 1 < next.size && next[first + 1].receivedAtMs <= cutoff) {
            first++
        }
        return History(next.drop(first).takeLast(MAX_SAMPLES))
    }

    /**
     * Sum only positive increments ending within the window. A resetting
     * sensor or source switch cannot invent movement from a negative jump.
     * Saturate Int arithmetic; never wrap into a misleading negative value.
     */
    fun evidence(history: History, nowMs: Long): Evidence? {
        if (!valid(history) || nowMs <= 0L ||
            history.samples.lastOrNull()?.receivedAtMs?.let { it > nowMs } == true
        ) return null
        val cutoff = nowMs - STEP_LOOKBACK_MIN * MINUTE_MS
        var total = 0L
        for (i in 1 until history.samples.size) {
            val current = history.samples[i]
            if (current.receivedAtMs <= cutoff) continue
            val increment = (current.stepsToday.toLong() -
                history.samples[i - 1].stepsToday.toLong()).coerceAtLeast(0L)
            total = (total + increment).coerceAtMost(Int.MAX_VALUE.toLong())
        }
        val count = total.toInt()
        return Evidence(
            growthSteps = count,
            clearsHrAssistedStepThreshold = count >= STEP_WITH_HR_MIN,
            clearsStrongStepThreshold = count >= STRONG_STEP_MIN,
        )
    }

    /**
     * Legacy 2026-08-01 fix: observed cycle spacing, not mere invocation
     * count, controls the required 10 minutes of elevated heart rate.
     */
    fun hrSustainCycles(spacingMinutes: Double?): Int? {
        if (spacingMinutes?.let { !it.isFinite() || it <= 0.0 } == true) return null
        val spacing = (spacingMinutes ?: 5.0).coerceAtLeast(0.5)
        return (HR_SUSTAIN_MIN / spacing).roundToInt().coerceAtLeast(1)
    }

    fun reliableFreshHr(sampleCountLast15Min: Int): Boolean? =
        if (sampleCountLast15Min < 0) null
        else sampleCountLast15Min >= MIN_FRESH_HR_SAMPLES
}
