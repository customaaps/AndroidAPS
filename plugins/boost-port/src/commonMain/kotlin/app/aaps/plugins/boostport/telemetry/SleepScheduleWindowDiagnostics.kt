package app.aaps.plugins.boostport.telemetry

/**
 * Pure KMP extraction of SleepStateDetector's local-clock night scheduling.
 *
 * Boundaries preserve the source's half-open wrapped ranges: at exact
 * nightEnd, the base night ends; any configured lie-in window starts.
 * The +/-90-minute early sleep-candidate and wake-grace tests are
 * independent of pre-sleep lead time. A lie-in never starts new sleep
 * candidacy after the configured end.
 *
 * These are offline diagnostics only, not actual sleep-mode transitions
 * and NOT target, ISF, SMB, basal or pump instructions.
 */
internal object SleepScheduleWindowDiagnostics {
    const val SCHEDULE_TOLERANCE_MIN = 90
    const val MINUTES_PER_DAY = 1440

    data class Schedule(
        val nightStartMinute: Int,
        val nightEndMinute: Int,
        val preSleepLeadMinutes: Int = 60,
        val sleepInWindowMinutes: Int = 0,
    )

    data class Windows(
        val inBaseNight: Boolean,
        val preSleep: Boolean,
        val sleepCandidateAllowed: Boolean,
        val nearScheduledWake: Boolean,
        val inLieIn: Boolean,
        val remainInNightHold: Boolean,
    )

    private fun wrappedMinute(minute: Int, start: Int, endExclusive: Int): Boolean =
        when {
            start == endExclusive -> false
            endExclusive > start -> minute >= start && minute < endExclusive
            else -> minute >= start || minute < endExclusive
        }

    private fun subtract(minute: Int, lead: Int): Int =
        (minute - lead + MINUTES_PER_DAY) % MINUTES_PER_DAY

    private fun add(minute: Int, lead: Int): Int =
        (minute + lead) % MINUTES_PER_DAY

    fun inspect(schedule: Schedule, minuteOfDay: Int): Windows? {
        val s = schedule
        if (minuteOfDay !in 0 until MINUTES_PER_DAY ||
            s.nightStartMinute !in 0 until MINUTES_PER_DAY ||
            s.nightEndMinute !in 0 until MINUTES_PER_DAY ||
            s.nightStartMinute == s.nightEndMinute ||
            s.preSleepLeadMinutes !in 0..SCHEDULE_TOLERANCE_MIN ||
            s.sleepInWindowMinutes !in 0..720 ||
            // A too-long lie-in would wrap back into the core night and
            // make "morning" an all-day hold.
            s.sleepInWindowMinutes >=
                (s.nightStartMinute - s.nightEndMinute + MINUTES_PER_DAY) %
                    MINUTES_PER_DAY
        ) return null

        val min = minuteOfDay
        val early = subtract(s.nightStartMinute, SCHEDULE_TOLERANCE_MIN)
        val pre = subtract(s.nightStartMinute, s.preSleepLeadMinutes)
        val grace = subtract(s.nightEndMinute, SCHEDULE_TOLERANCE_MIN)
        val lieInEnd = add(s.nightEndMinute, s.sleepInWindowMinutes)

        val inLieIn = s.sleepInWindowMinutes > 0 &&
            wrappedMinute(min, s.nightEndMinute, lieInEnd)
        return Windows(
            inBaseNight = wrappedMinute(min, s.nightStartMinute, s.nightEndMinute),
            preSleep = wrappedMinute(min, pre, s.nightStartMinute),
            sleepCandidateAllowed = wrappedMinute(min, early, s.nightEndMinute),
            nearScheduledWake = wrappedMinute(min, grace, s.nightEndMinute),
            inLieIn = inLieIn,
            remainInNightHold = wrappedMinute(min, s.nightStartMinute, lieInEnd),
        )
    }
}
