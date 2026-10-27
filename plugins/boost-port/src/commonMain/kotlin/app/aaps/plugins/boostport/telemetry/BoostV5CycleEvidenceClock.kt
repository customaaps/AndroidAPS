package app.aaps.plugins.boostport.telemetry

/**
 * Passive Kotlin Multiplatform adaptation of the wall-clock evidence rules in
 * legacy Boost V5 DetermineBasalBoostV5 (2026-08-01 and 2026-10-08 fixes).
 *
 * Pure value-in/value-out diagnostic logic. It does not poll CGM, score a meal
 * for dosing, change any therapy setting, write AAPS Preferences, or issue
 * pump/SMB requests.
 *
 * One-minute and repeated invocations must NOT count as five-minute samples.
 * In the original V5 code the minimum tick is four minutes, not five.
 */
internal object BoostV5CycleEvidenceClock {
    const val AGE_TICK_MS = 4L * 60_000L
    const val CONFIRM_SCORE = 0.55
    const val RENORMALIZE_AFTER_MISSING_TICKS = 3

    data class State(
        val missingMlTicks: Int = 0,
        val missingMlTickAtMs: Long = 0L,
        val lastCycleScore: Double? = null,
        val scoreReadySinceMs: Long = 0L,
    )

    data class Output(
        val state: State,
        val sustainedScoreReady: Boolean,
        val wouldRenormalizeMissingMl: Boolean,
    )

    /**
     * Consume a caller-provided *completed diagnostic* ML/meal-score sample.
     *
     * Mirrors the original:
     * - absent ML advances the null counter no faster than 4 min per tick;
     * - available ML resets the count (but keeps the previous tick anchor);
     * - a continuous >=0.55 score must span >=4 min to become ready;
     * - a user/loop reset breaks the score-ready run;
     * - cold start / process restart has no stored score-ready clock.
     *
     * Unknown/nonmonotonic timestamps or corrupt input fail closed with null.
     */
    fun advance(
        before: State,
        nowMs: Long,
        mlMealLikely: Double?,
        score: Double,
        stateReset: Boolean = false,
    ): Output? {
        if (nowMs < 0L || !score.isFinite() || score !in 0.0..1.0 ||
            mlMealLikely?.let { !it.isFinite() || it !in 0.0..1.0 } == true ||
            before.missingMlTicks < 0 ||
            before.missingMlTickAtMs < 0L || before.scoreReadySinceMs < 0L ||
            before.lastCycleScore?.let { !it.isFinite() || it !in 0.0..1.0 } == true ||
            (nowMs > 0L && (before.missingMlTickAtMs > nowMs ||
                before.scoreReadySinceMs > nowMs))
        ) return null
        val unknownClock = nowMs <= 0L
        val mayTick = unknownClock || before.missingMlTickAtMs == 0L ||
            nowMs - before.missingMlTickAtMs >= AGE_TICK_MS
        val nextMissing = when {
            mlMealLikely != null -> 0
            mayTick -> if (before.missingMlTicks == Int.MAX_VALUE) return null
                else before.missingMlTicks + 1
            else -> before.missingMlTicks
        }
        val nextMissingAt = if (mlMealLikely == null && !unknownClock && mayTick)
            nowMs else before.missingMlTickAtMs

        // Legacy helper checks readiness BEFORE recording this cycle's score.
        val ready = if (unknownClock) {
            (before.lastCycleScore ?: 0.0) >= CONFIRM_SCORE
        } else {
            !stateReset && before.scoreReadySinceMs > 0L &&
                nowMs - before.scoreReadySinceMs >= AGE_TICK_MS
        }
        val nextReadySince = when {
            unknownClock || score < CONFIRM_SCORE -> 0L
            !stateReset && before.scoreReadySinceMs > 0L -> before.scoreReadySinceMs
            else -> nowMs
        }
        return Output(
            state = State(nextMissing, nextMissingAt, score, nextReadySince),
            sustainedScoreReady = ready,
            wouldRenormalizeMissingMl = mlMealLikely == null &&
                nextMissing >= RENORMALIZE_AFTER_MISSING_TICKS,
        )
    }

    /**
     * Simulate a cold process restart: the legacy preference JSON stores the
     * missing-ML count but NOT its tick clock, last score or score-ready clock.
     * The returned state cannot authorize early-confirm evidence on restart.
     */
    fun restoredFromPersistedNullStreak(missingTicks: Int): State? =
        if (missingTicks < 0) null else State(missingMlTicks = missingTicks)
}
