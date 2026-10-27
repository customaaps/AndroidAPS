package app.aaps.plugins.boostport.telemetry

/**
 * Offline KMP inspection of Boost V6 MealTimeLearner.preMealWindow.
 *
 * Legacy rule: on an already-trusted learned meal mode, the circular
 * minutes-ahead distance is inside the inclusive 45..openBefore window.
 * The configurable early boundary is at least 55 minutes (45 + 10).
 *
 * This returns a HISTORICAL MATCH LABEL only. It never requests a pre-meal
 * temp target, changes the BG target, touches an APS insulin/SMB profile,
 * schedules notifications, or connects to live CGM/pump data.
 */
internal object MealLeadWindowAudit {
    const val CLOSE_BEFORE_MIN = 45
    const val MIN_WINDOW_SPAN_MIN = 10
    private const val DAY_MS = 86_400_000L
    private const val MINUTE_MS = 60_000L

    data class Hit(
        val centreMinute: Int,
        val minutesBeforeMeal: Int,
        val supportingEvents: Int,
        val supportingDays: Int,
    )

    data class Inspection(
        val currentLocalMinute: Int,
        val openBeforeMinutes: Int,
        val trustedModeCount: Int,
        val historicalMatch: Hit?,
    )

    /**
     * The order of candidate modes comes from MealTimeDiagnostics.modes:
     * highest event count, then distinct days. The original V6 feature
     * accepts the FIRST matched mode, not the closest one.
     */
    fun inspect(
        history: MealEventHistoryAudit.History,
        nowMs: Long,
        localOffsetMs: Long,
        nightStartMin: Int,
        nightEndMin: Int,
        leadMaxMin: Int,
    ): Inspection? {
        if (nowMs <= 0L || history.eventsMs.size > 500 ||
            MealEventHistoryAudit.encode(history) == null ||
            localOffsetMs !in (-18L * 3_600_000L)..(18L * 3_600_000L) ||
            (localOffsetMs > 0L && nowMs > Long.MAX_VALUE - localOffsetMs) ||
            nowMs + localOffsetMs < 0L ||
            nightStartMin !in 0..1439 || nightEndMin !in 0..1439 ||
            leadMaxMin !in 0..1440
        ) return null

        val localMinute = ((nowMs + localOffsetMs) % DAY_MS / MINUTE_MS).toInt()
        val open = leadMaxMin.coerceAtLeast(
            CLOSE_BEFORE_MIN + MIN_WINDOW_SPAN_MIN
        )
        val modes = MealTimeDiagnostics.modes(
            confirmedMealTimestampsMs = history.eventsMs,
            nowMs = nowMs,
            localOffsetMs = localOffsetMs,
            nightStartMin = nightStartMin,
            nightEndMin = nightEndMin,
        )
        val hit = modes.firstNotNullOfOrNull { mode ->
            val ahead = (mode.centreMin - localMinute + 1440) % 1440
            if (ahead in CLOSE_BEFORE_MIN..open) Hit(
                centreMinute = mode.centreMin,
                minutesBeforeMeal = ahead,
                supportingEvents = mode.eventCount,
                supportingDays = mode.distinctDays,
            ) else null
        }
        return Inspection(localMinute, open, modes.size, hit)
    }
}
