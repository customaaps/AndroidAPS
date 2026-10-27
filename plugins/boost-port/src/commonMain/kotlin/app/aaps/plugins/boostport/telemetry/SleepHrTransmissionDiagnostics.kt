package app.aaps.plugins.boostport.telemetry

/**
 * Inert KMP replay of the signal-quality and HR-transmission-resume policy
 * in legacy SleepStateDetector (July / October 2026 fixes).
 *
 * A batch of HR records following a drought must NOT, by itself, be treated
 * as a genuine wake in the core night. Near the scheduled wake and during
 * lie-in, the legacy signal policy permits that burst without steps.
 *
 * All observations are supplied by an offline diagnostic caller. The
 * returned labels cannot turn night mode on/off, adjust targets, alter
 * Dmitry's correction order, influence SMB, or operate a pump.
 */
internal object SleepHrTransmissionDiagnostics {
    const val FRESH_RELIABLE_COUNT = 3
    const val RESUME_MIN_STEPS = 20
    const val SCHEDULE_GRACE_MIN = 90
    private const val MINUTE_MS = 60_000L

    data class Sample(val timestampMs: Long, val valid: Boolean)

    data class Input(
        val nowMs: Long,
        val minuteOfDay: Int,
        val nightEndMinute: Int,
        val sleepInWindowMinutes: Int,
        val droughtThresholdMinutes: Int,
        val freshWindowMinutes: Int,
        val previouslyReliableSampleMs: Long,
        val stepsLast15Min: Int?,
        val cumulativeStepGrowth60Min: Int?,
        val readings: List<Sample>,
    )

    data class Evidence(
        val freshSamplesLast15Min: Int,
        val latestFreshSampleMs: Long?,
        val nextReliableSampleMs: Long,
        val priorDroughtMinutes: Long?,
        val currentDroughtMinutes: Long?,
        val reliableFeed: Boolean,
        val eligibleResumeBurst: Boolean,
        val corroboration: String?,
    )

    private fun containsMinute(minute: Int, start: Int, end: Int): Boolean =
        if (start == end) false
        else if (end > start) minute in start until end
        else minute >= start || minute < end

    fun inspect(input: Input): Evidence? {
        val i = input
        if (i.nowMs <= 0L ||
            i.minuteOfDay !in 0..1439 || i.nightEndMinute !in 0..1439 ||
            i.sleepInWindowMinutes !in 0..1439 ||
            i.droughtThresholdMinutes !in 1..1440 ||
            i.freshWindowMinutes !in 1..1440 ||
            i.previouslyReliableSampleMs < 0L ||
            i.previouslyReliableSampleMs > i.nowMs ||
            i.stepsLast15Min?.let { it < 0 } == true ||
            i.cumulativeStepGrowth60Min?.let { it < 0 } == true ||
            i.readings.size > 10_000 ||
            i.readings.any { it.timestampMs < 0L }
        ) return null

        val freshCutoff = (i.nowMs - i.freshWindowMinutes * MINUTE_MS)
            .coerceAtLeast(0L)
        val last15 = (i.nowMs - 15L * MINUTE_MS).coerceAtLeast(0L)
        val fresh = i.readings.filter { it.valid &&
            it.timestampMs > freshCutoff && it.timestampMs <= i.nowMs }
        val latest = fresh.maxOfOrNull { it.timestampMs }
        val count = fresh.count { it.timestampMs >= last15 }
        val reliable = count >= FRESH_RELIABLE_COUNT
        val nextReliable = if (reliable && latest != null &&
            latest > i.previouslyReliableSampleMs) latest
        else i.previouslyReliableSampleMs

        fun droughtSince(anchorMs: Long): Long? =
            if (anchorMs > 0L) (i.nowMs - anchorMs) / MINUTE_MS else null
        val priorDrought = droughtSince(i.previouslyReliableSampleMs)
        val currentDrought = droughtSince(nextReliable)
        val priorEstablished = priorDrought == null ||
            priorDrought >= i.droughtThresholdMinutes
        val graceStart = (i.nightEndMinute - SCHEDULE_GRACE_MIN + 1440) % 1440
        val nearWake = containsMinute(i.minuteOfDay, graceStart, i.nightEndMinute)
        val lieInEnd = (i.nightEndMinute + i.sleepInWindowMinutes) % 1440
        val inLieIn = i.sleepInWindowMinutes > 0 &&
            containsMinute(i.minuteOfDay, i.nightEndMinute, lieInEnd)

        val steps = (i.stepsLast15Min ?: 0) >= RESUME_MIN_STEPS ||
            (i.cumulativeStepGrowth60Min ?: 0) >= RESUME_MIN_STEPS
        val reason = when {
            nearWake -> "scheduled-wake-grace"
            inLieIn -> "lie-in"
            steps -> "steps"
            else -> null
        }
        val burst = reliable && priorEstablished
        return Evidence(
            freshSamplesLast15Min = count,
            latestFreshSampleMs = latest,
            nextReliableSampleMs = nextReliable,
            priorDroughtMinutes = priorDrought,
            currentDroughtMinutes = currentDrought,
            reliableFeed = reliable,
            eligibleResumeBurst = burst && reason != null,
            corroboration = if (burst) reason else null,
        )
    }
}
