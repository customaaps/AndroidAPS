package app.aaps.plugins.boostport.telemetry

/**
 * Passive Kotlin Multiplatform replay of Boost V6 MealTimeLearner.History
 * and its 60-day confirmed-meal retention rule.
 *
 * The source persists {"events":[utcMs,...]} in Preferences. This adapter
 * works ONLY on immutable caller-supplied values and schema-like lists;
 * it does not load JSON, read/write Preferences, infer meals, set temp
 * glucose targets, recommend insulin, or connect to any APS/pump path.
 */
internal object MealEventHistoryAudit {
    const val WINDOW_DAYS = MealTimeDiagnostics.WINDOW_DAYS
    private const val DAY_MS = 86_400_000L
    private const val MAX_EVENTS = 500

    data class History(val eventsMs: List<Long> = emptyList())

    data class Projection(
        val history: History,
        val droppedOutOfWindow: Int,
        val appendedNewCommit: Boolean,
    )

    /**
     * Consume a *caller-confirmed* V5 fresh meal-commit only. A repeated
     * identical timestamp is a replay of the same commit, not six meals.
     * Reject corrupt, out-of-order or future data rather than treating it
     * as enough evidence to train an automatic meal-time event.
     *
     * Unlike the mutable legacy list, the returned value cannot modify
     * the input history.
     */
    fun record(
        before: History,
        confirmedCommitMs: Long,
        nowMs: Long,
    ): Projection? {
        if (nowMs <= 0L || confirmedCommitMs <= 0L || confirmedCommitMs > nowMs ||
            before.eventsMs.size > MAX_EVENTS ||
            before.eventsMs.any { it <= 0L || it > nowMs } ||
            before.eventsMs.zipWithNext().any { (a, b) -> b <= a } ||
            before.eventsMs.lastOrNull()?.let { confirmedCommitMs < it } == true
        ) return null

        val cutoff = (nowMs - WINDOW_DAYS * DAY_MS).coerceAtLeast(0L)
        val retained = before.eventsMs.filter { it >= cutoff }
        val append = confirmedCommitMs >= cutoff &&
            retained.lastOrNull() != confirmedCommitMs
        if (append && retained.size >= MAX_EVENTS) return null
        val next = if (append) retained + confirmedCommitMs else retained
        return Projection(
            history = History(next),
            droppedOutOfWindow = before.eventsMs.size - retained.size,
            appendedNewCommit = append,
        )
    }

    /** The one-key JSON source schema, without JSON or persistence. */
    fun encode(history: History): Map<String, List<String>>? {
        if (history.eventsMs.size > MAX_EVENTS ||
            history.eventsMs.any { it <= 0L } ||
            history.eventsMs.zipWithNext().any { (a, b) -> b <= a }
        ) return null
        return mapOf("events" to history.eventsMs.map { it.toString() })
    }

    /**
     * Strict offline restoration. Missing/corrupt event arrays return null,
     * not an assumed historical meal pattern. A caller can explicitly
     * supply History() for the legacy cold-start empty state.
     */
    fun decode(fields: Map<String, List<String>>): History? {
        val raw = fields["events"] ?: return null
        if (raw.size > MAX_EVENTS) return null
        val timestamps = raw.map { it.toLongOrNull() ?: return null }
        if (timestamps.any { it <= 0L } ||
            timestamps.zipWithNext().any { (a, b) -> b <= a }
        ) return null
        return History(timestamps)
    }

    /**
     * Drop configured night-time meal events from a supplied snapshot.
     * Delegates the clock semantics to the already staged V6 cluster audit.
     */
    fun withoutNight(
        history: History,
        localOffsetMs: Long,
        nightStartMin: Int,
        nightEndMin: Int,
    ): History? {
        if (history.eventsMs.size > MAX_EVENTS ||
            history.eventsMs.any { it <= 0L } ||
            history.eventsMs.zipWithNext().any { (a, b) -> b <= a } ||
            localOffsetMs !in (-18L * 3_600_000L)..(18L * 3_600_000L) ||
            nightStartMin !in 0..1439 || nightEndMin !in 0..1439
        ) return null

        val kept = mutableListOf<Long>()
        for (ts in history.eventsMs) {
            if (localOffsetMs > 0L && ts > Long.MAX_VALUE - localOffsetMs)
                return null
            val localMs = ts + localOffsetMs
            if (localMs < 0L) return null
            val minute = ((localMs % DAY_MS) / 60_000L).toInt()
            if (!MealTimeDiagnostics.inNightMinutes(minute, nightStartMin, nightEndMin))
                kept += ts
        }
        return History(kept)
    }
}
