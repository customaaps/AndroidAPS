package app.aaps.plugins.boostport.telemetry

/**
 * Passive KMP port of SleepHistoryTracker's lifecycle around a confirmed
 * sleep-start and wake. Complements SleepHistoryDiagnostics aggregation.
 *
 * All events must be explicitly supplied by an offline caller. This class
 * does NOT detect sleep, read sensors, set targets, persist JSON, update
 * preferences, invoke APS, control SMB or communicate with a pump.
 */
internal object SleepSessionHistoryDiagnostics {
    const val WINDOW_DAYS = 28L
    private const val DAY_MS = 86_400_000L
    private const val MAX_SESSIONS = 128
    private const val MAX_HR_SAMPLES = 50_000

    data class History(
        val sessions: List<SleepHistoryDiagnostics.Session> = emptyList(),
        val openSleepStartMs: Long? = null,
    )

    data class CloseResult(
        val history: History,
        val recorded: Boolean,
        val wakeIsGenuine: Boolean,
    )

    private val genuineWakeReasons = setOf("hr_steps", "resume", "steps")
    private val knownWakeReasons = genuineWakeReasons + "boundary"

    /**
     * Unknown legacy reasons are permitted as an untrained wake label, but
     * are never promoted to evidence for a learned wake time.
     */
    fun isGenuineWake(wakeReason: String?): Boolean = wakeReason in genuineWakeReasons

    private fun valid(history: History): Boolean =
        history.sessions.size <= MAX_SESSIONS &&
            history.openSleepStartMs?.let { it <= 0L } != true &&
            history.sessions.all {
                it.sleepStartMs > 0L && it.wakeMs > it.sleepStartMs &&
                    it.wakeMs - it.sleepStartMs <= DAY_MS &&
                    it.sleepHrP10?.let { bpm -> bpm <= 0 } != true &&
                    it.daytimeHrP10?.let { bpm -> bpm <= 0 } != true
            }

    /** A second sleep-start cannot erase an already-open session. */
    fun start(history: History, startMs: Long): History? {
        if (!valid(history) || startMs <= 0L || history.openSleepStartMs != null ||
            history.sessions.any { startMs <= it.wakeMs }
        ) return null
        return history.copy(openSleepStartMs = startMs)
    }

    /** Invalid or stale input causes abstention, not a phantom sleep event. */
    fun wake(
        history: History,
        wakeMs: Long,
        sleepHrBpms: List<Double> = emptyList(),
        daytimeHrBpms: List<Double> = emptyList(),
        wakeReason: String? = null,
    ): CloseResult? {
        if (!valid(history) || wakeMs <= 0L ||
            sleepHrBpms.size > MAX_HR_SAMPLES ||
            daytimeHrBpms.size > MAX_HR_SAMPLES ||
            (wakeReason != null && wakeReason !in knownWakeReasons)
        ) return null
        val start = history.openSleepStartMs ?: return CloseResult(
            history, recorded = false, wakeIsGenuine = false)
        if (wakeMs <= start || wakeMs - start > DAY_MS) return null

        val session = SleepHistoryDiagnostics.Session(
            sleepStartMs = start,
            wakeMs = wakeMs,
            wakeReason = wakeReason,
            sleepHrP10 = SleepHistoryDiagnostics.p10(sleepHrBpms),
            daytimeHrP10 = SleepHistoryDiagnostics.p10(daytimeHrBpms),
        )
        val cutoff = (wakeMs - WINDOW_DAYS * DAY_MS).coerceAtLeast(0L)
        val sessions = (history.sessions + session)
            .filter { it.sleepStartMs >= cutoff && it.wakeMs <= wakeMs }
            .takeLast(MAX_SESSIONS)
        return CloseResult(
            History(sessions, null),
            recorded = true,
            wakeIsGenuine = isGenuineWake(wakeReason),
        )
    }

    fun lastWakeMs(history: History): Long? =
        if (!valid(history)) null else history.sessions.maxOfOrNull { it.wakeMs }

    /** Summarise closed sessions only; never infer a wake from an open session. */
    fun summarize(history: History, nowMs: Long, localOffsetMs: Long):
        SleepHistoryDiagnostics.Summary? =
        if (!valid(history)) null
        else SleepHistoryDiagnostics.summarize(history.sessions, nowMs, localOffsetMs)
}
