package app.aaps.plugins.boostport.telemetry

import kotlin.math.abs
import kotlin.math.max

/**
 * Read-only, state-in/state-out KMP replay of Boost V5 MealHypothesis.
 *
 * Ports V5's timing, score/offset peak expiry and single-commit session-lock
 * semantics for regression inspection. There is NO plugin registration,
 * persistence, dosing, SMB command, APS call, scheduler or target manipulation.
 * A CONFIRMED label in this replay is NOT a request to deliver any insulin.
 */
internal object BoostV5MealStateDiagnostics {
    const val ENTER_SCORE = 0.44
    const val CONFIRM_SCORE = 0.55
    const val CONFIRM_OFFSET = 30.0
    const val FALLBACK_SCORE = 0.36
    const val RECOVERING_EXIT_SCORE = 0.18
    const val AGE_TICK_MS = 4L * 60_000L
    const val MAX_TIME_JUMP_MIN = 30.0
    const val PEAK_VALID_MS = 30L * 60_000L
    const val SESSION_LOCK_MIN_MS = 90L * 60_000L
    const val NON_POSITIVE_RELEASE_MS = 30L * 60_000L
    const val FAST_DELTA = 6.0
    const val FAST_ACCEL = 10.0
    const val FAST_SCORE = 0.65
    const val FAST_RECENT_LOW_FLOOR = 80.0

    enum class Phase { IDLE, OBSERVING, CONFIRMED, COMMITTED, RECOVERING }

    data class State(
        val phase: Phase = Phase.IDLE,
        val ageCycles: Int = 0,
        val peakScore: Double = 0.0,
        val peakOffset: Double = 0.0,
        val committedInSession: Boolean = false,
        val lastAgeMs: Long = 0L,
        val lastCommitMs: Long = 0L,
        val nonPositiveStartMs: Long = 0L,
        val peakScoreAtMs: Long = 0L,
        val peakOffsetAtMs: Long = 0L,
    )

    data class Input(
        val score: Double,
        val eventualBg: Double,
        val targetBg: Double,
        val delta: Double,
        val deltaAcceleration: Double,
        val deltaDeclining: Boolean,
        val asleep: Boolean = false,
        val exerciseActive: Boolean = false,
        val fastConfirmEnabled: Boolean = false,
        // Already evaluated by the legacy caller; this replay NEVER sizes a dose.
        val legacyDoseAdequacyGatePassed: Boolean = true,
        val scoreReadyStreak: Boolean = false,
        val aggressiveEarlyConfirm: Boolean = false,
        val nowMs: Long = 0L,
        val mealAnnounced: Boolean = false,
    )

    fun fastConfirmAllowed(userEnabled: Boolean, recentLowBg: Double): Boolean =
        userEnabled && recentLowBg.isFinite() && recentLowBg >= FAST_RECENT_LOW_FLOOR

    /**
     * Audit-only parity helper: returns the historic V5 floor as an opaque
     * threshold. It is NEVER applied or exported as a dosing setting.
     */
    fun historicalConfirmFloor(committedCapU: Double, confirmedCapU: Double): Double? =
        if (committedCapU.isFinite() && confirmedCapU.isFinite() &&
            committedCapU >= 0.0 && confirmedCapU >= 0.0
        ) minOf(minOf(committedCapU, 0.5), 0.8 * confirmedCapU) else null

    /** Expire peaks after 30m. With unknown clock, follow legacy running max. */
    private fun windowedPeak(peak: Double, atMs: Long, value: Double, nowMs: Long): Pair<Double, Long> =
        when {
            nowMs <= 0L -> max(peak, value) to atMs
            value >= peak || atMs <= 0L || nowMs < atMs ||
                nowMs - atMs > PEAK_VALID_MS -> value to nowMs
            else -> peak to atMs
        }

    /** Preserve the 90m lock through RECOVERING -> IDLE and state resets. */
    fun releaseLock(state: State, nowMs: Long, delta: Double): State {
        if (nowMs <= 0L || !state.committedInSession || !delta.isFinite()) return state
        if (state.phase == Phase.CONFIRMED || state.phase == Phase.COMMITTED) return state
        if (state.lastCommitMs <= 0L) return state.copy(lastCommitMs = nowMs)
        if (state.lastCommitMs > nowMs) return state
        val expired = nowMs - state.lastCommitMs >= SESSION_LOCK_MIN_MS
        val runStart = max(state.nonPositiveStartMs, state.lastCommitMs)
        val sustainedDrop = delta <= 0.0 && state.nonPositiveStartMs > 0L &&
            runStart <= nowMs && nowMs - runStart >= NON_POSITIVE_RELEASE_MS
        return if (expired || sustainedDrop) state.copy(committedInSession = false) else state
    }

    fun staleStateMinutes(state: State, nowMs: Long): Double =
        if (nowMs <= 0L || state.lastAgeMs <= 0L) 0.0
        else abs(nowMs.toDouble() - state.lastAgeMs.toDouble()) / 60_000.0

    fun resetIfNeeded(
        state: State,
        profileSwitched: Boolean = false,
        pumpDisconnected: Boolean = false,
        loopSuspended: Boolean = false,
        timeJumpMinutes: Double = 0.0,
    ): Pair<State, Boolean> {
        val reset = profileSwitched || pumpDisconnected || loopSuspended ||
            !timeJumpMinutes.isFinite() || timeJumpMinutes > MAX_TIME_JUMP_MIN
        return if (reset) State(
            committedInSession = state.committedInSession,
            lastCommitMs = state.lastCommitMs,
        ) to true else state to false
    }

    fun deltaDeclining(history: List<Double>, windowCycles: Int = 2): Boolean {
        if (windowCycles <= 0 || windowCycles >= history.size ||
            history.any { !it.isFinite() }) return false
        val tail = history.takeLast(windowCycles + 1)
        return tail.zipWithNext().all { (earlier, later) -> earlier > later }
    }

    private fun fallback(s: State, score: Double) =
        score < FALLBACK_SCORE && s.ageCycles >= 2

    /**
     * The same slow-confirm predicate used for V5's telemetry and transition,
     * EXCLUDING the caller's original dose-adequacy test.
     */
    fun eligibleExceptLegacyDoseGate(
        state: State,
        input: Input,
    ): Boolean {
        if (state.phase != Phase.OBSERVING || state.committedInSession) return false
        if (fallback(state, input.score)) return false
        val scorePeak = windowedPeak(state.peakScore, state.peakScoreAtMs,
            input.score, input.nowMs).first
        val offsetPeak = windowedPeak(state.peakOffset, state.peakOffsetAtMs,
            input.eventualBg - input.targetBg, input.nowMs).first
        val earlyAge = if (input.aggressiveEarlyConfirm) 0 else 1
        val ageAllowed = state.ageCycles >= 2 ||
            (state.ageCycles >= earlyAge && input.score >= CONFIRM_SCORE && input.scoreReadyStreak)
        return ageAllowed && scorePeak >= CONFIRM_SCORE && offsetPeak >= CONFIRM_OFFSET
    }

    /**
     * Run exactly one historic hypothesis transition for a supplied sample.
     * Invalid or out-of-order inputs abstain (null) rather than fabricating a
     * result for an active therapy controller.
     */
    fun step(current: State, input: Input): State? {
        if (input.nowMs < 0L || !input.score.isFinite() ||
            !input.eventualBg.isFinite() || !input.targetBg.isFinite() ||
            !input.delta.isFinite() || !input.deltaAcceleration.isFinite() ||
            input.score !in 0.0..1.0 || current.ageCycles < 0 ||
            !current.peakScore.isFinite() || !current.peakOffset.isFinite() ||
            current.lastAgeMs < 0L || current.lastCommitMs < 0L ||
            current.nonPositiveStartMs < 0L ||
            current.peakScoreAtMs < 0L || current.peakOffsetAtMs < 0L ||
            (input.nowMs > 0L && current.lastAgeMs > input.nowMs)
        ) return null
        val cur = releaseLock(current, input.nowMs, input.delta)
        val timed = input.nowMs > 0L
        val ageTick = !timed || cur.lastAgeMs <= 0L ||
            input.nowMs - cur.lastAgeMs >= AGE_TICK_MS
        val newAge = if (ageTick) {
            if (cur.ageCycles == Int.MAX_VALUE) return null
            cur.ageCycles + 1
        } else cur.ageCycles
        val tickMs = if (ageTick && timed) input.nowMs else cur.lastAgeMs
        val enterMs = if (timed) input.nowMs else cur.lastAgeMs
        val keepLock = timed && cur.committedInSession
        val offset = input.eventualBg - input.targetBg
        if (!offset.isFinite()) return null

        fun stable() = State(
            phase = cur.phase,
            ageCycles = newAge,
            committedInSession = cur.committedInSession,
            lastAgeMs = tickMs,
        )
        fun confirmed() = State(
            phase = if (input.mealAnnounced) Phase.COMMITTED else Phase.CONFIRMED,
            committedInSession = true,
            lastAgeMs = enterMs,
        )
        val fast = input.fastConfirmEnabled && !input.asleep &&
            !input.exerciseActive && !cur.committedInSession &&
            input.delta >= FAST_DELTA && input.deltaAcceleration >= FAST_ACCEL &&
            input.score >= FAST_SCORE
        val next = when (cur.phase) {
            Phase.IDLE -> when {
                fast -> confirmed()
                input.score >= ENTER_SCORE -> State(
                    phase = Phase.OBSERVING,
                    peakScore = input.score,
                    peakOffset = offset,
                    committedInSession = keepLock,
                    lastAgeMs = enterMs,
                    peakScoreAtMs = if (timed) input.nowMs else 0L,
                    peakOffsetAtMs = if (timed) input.nowMs else 0L,
                )
                else -> stable()
            }

            Phase.OBSERVING -> {
                val (scorePeak, scoreAt) = windowedPeak(
                    cur.peakScore, cur.peakScoreAtMs, input.score, input.nowMs)
                val (offsetPeak, offsetAt) = windowedPeak(
                    cur.peakOffset, cur.peakOffsetAtMs, offset, input.nowMs)
                val ordinary = eligibleExceptLegacyDoseGate(cur, input) &&
                    input.legacyDoseAdequacyGatePassed
                val reengage = cur.committedInSession &&
                    input.legacyDoseAdequacyGatePassed &&
                    eligibleExceptLegacyDoseGate(
                        cur.copy(committedInSession = false), input)
                when {
                    fallback(cur, input.score) -> State(
                        committedInSession = keepLock, lastAgeMs = enterMs)
                    fast || ordinary -> confirmed()
                    reengage -> State(phase = Phase.COMMITTED,
                        committedInSession = true, lastAgeMs = enterMs)
                    else -> State(
                        phase = Phase.OBSERVING,
                        ageCycles = newAge,
                        peakScore = scorePeak, peakOffset = offsetPeak,
                        committedInSession = cur.committedInSession,
                        lastAgeMs = tickMs,
                        peakScoreAtMs = scoreAt, peakOffsetAtMs = offsetAt,
                    )
                }
            }

            Phase.CONFIRMED -> State(
                phase = Phase.COMMITTED,
                committedInSession = true,
                lastAgeMs = enterMs,
            )

            Phase.COMMITTED -> if (input.deltaAcceleration < -5.0 && input.deltaDeclining)
                State(phase = Phase.RECOVERING,
                    committedInSession = true, lastAgeMs = enterMs)
            else stable()

            Phase.RECOVERING -> when {
                cur.ageCycles >= 1 &&
                    input.deltaAcceleration > 10.0 &&
                    input.delta > 3.0 && offset > 20.0 ->
                    State(phase = Phase.COMMITTED,
                        committedInSession = true, lastAgeMs = enterMs)
                input.delta < 0.0 || input.score < RECOVERING_EXIT_SCORE ->
                    State(committedInSession = keepLock, lastAgeMs = enterMs)
                else -> stable()
            }
        }
        if (!timed) return next.copy(
            lastCommitMs = cur.lastCommitMs,
            nonPositiveStartMs = cur.nonPositiveStartMs,
        )
        val committing = next.phase == Phase.CONFIRMED || next.phase == Phase.COMMITTED
        return next.copy(
            lastCommitMs = if (committing) input.nowMs else cur.lastCommitMs,
            nonPositiveStartMs = when {
                input.delta > 0.0 -> 0L
                cur.nonPositiveStartMs > 0L -> cur.nonPositiveStartMs
                else -> input.nowMs
            },
        )
    }
}
