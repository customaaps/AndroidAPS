package app.aaps.plugins.boostport.telemetry

/**
 * Pure KMP staging of the *state labels and transition precedence* in legacy
 * SleepStateDetector, fed entirely by precomputed offline evidence.
 *
 * This is NOT the active detector: it neither reads HR/steps nor changes
 * night-mode dosing, SMB, target BG or basal. No live AAPS/Android/pump link
 * or persistent state; the caller supplies and receives immutable snapshots.
 */
internal object SleepPhaseReplayDiagnostics {
    private const val MINUTE_MS = 60_000L

    enum class Phase { AWAKE, PRE_SLEEP, SLEEPING }

    data class State(
        val phase: Phase = Phase.AWAKE,
        val enteredAtMs: Long = 0L,
        val sleepCandidateSinceMs: Long? = null,
        val wakeCandidateSinceMs: Long? = null,
        val sleepEntryReason: String? = null,
    )

    data class Evidence(
        val nowMs: Long,
        val windows: SleepScheduleWindowDiagnostics.Windows,
        val hrSleepCandidate: Boolean,
        val droughtSleepCandidate: Boolean,
        val averageHrAvailable: Boolean,
        val stepsGrowth60Min: Int?,
        val stepsLast15Min: Int?,
        val sustainedElevatedHr: Boolean,
        val resumeBurstCorroborated: Boolean,
        val droughtEstablished: Boolean,
        val strongStepThreshold: Int = 250,
        val minSleepHysteresisMinutes: Int = 10,
        val minWakeHysteresisMinutes: Int = 5,
    )

    data class Result(
        val state: State,
        val transitioned: Boolean,
        val wakeReason: String?,
    )

    /**
     * No action is authorized by a SLEEPING or PRE_SLEEP result. The
     * historical "boundary" reason is diagnostic and must not train a
     * learned genuine-wake time; only hr_steps/resume/steps are genuine.
     */
    fun replay(before: State, evidence: Evidence): Result? {
        val e = evidence
        if (e.nowMs <= 0L || before.enteredAtMs < 0L ||
            before.enteredAtMs > e.nowMs ||
            before.sleepCandidateSinceMs?.let { it <= 0L || it > e.nowMs } == true ||
            before.wakeCandidateSinceMs?.let { it <= 0L || it > e.nowMs } == true ||
            e.stepsGrowth60Min?.let { it < 0 } == true ||
            e.stepsLast15Min?.let { it < 0 } == true ||
            e.strongStepThreshold !in 1..100_000 ||
            e.minSleepHysteresisMinutes !in 0..240 ||
            e.minWakeHysteresisMinutes !in 0..240
        ) return null

        fun moved(phase: Phase, reason: String? = null, entry: String? = null) =
            Result(State(phase, enteredAtMs = e.nowMs, sleepEntryReason = entry),
                transitioned = before.phase != phase, wakeReason = reason)

        val qualifiesSleep = e.hrSleepCandidate || e.droughtSleepCandidate
        val growth = e.stepsGrowth60Min ?: 0
        val strong = growth >= e.strongStepThreshold
        val oldSleepCandidate = before.sleepCandidateSinceMs
        fun candidateStart() = before.copy(sleepCandidateSinceMs = e.nowMs)
        fun sleepReason() = when {
            e.hrSleepCandidate -> "hr"
            !e.averageHrAvailable -> "drought"
            else -> "time"
        }
        fun advanceSleep(): Result = when {
            oldSleepCandidate == null -> Result(candidateStart(), false, null)
            e.nowMs - oldSleepCandidate >=
                e.minSleepHysteresisMinutes.toLong() * MINUTE_MS ->
                    moved(Phase.SLEEPING, entry = sleepReason())
            else -> Result(before, false, null)
        }

        return when (before.phase) {
            Phase.AWAKE -> {
                val candidate = if (e.windows.sleepCandidateAllowed && qualifiesSleep)
                    advanceSleep()
                else Result(before.copy(sleepCandidateSinceMs = null), false, null)
                if (!candidate.transitioned && e.windows.preSleep)
                    moved(Phase.PRE_SLEEP)
                else candidate
            }

            Phase.PRE_SLEEP -> when {
                strong -> moved(Phase.AWAKE)
                !e.windows.inBaseNight && !e.windows.preSleep -> moved(Phase.AWAKE)
                qualifiesSleep -> advanceSleep()
                else -> Result(before.copy(sleepCandidateSinceMs = null), false, null)
            }

            Phase.SLEEPING -> when {
                !e.windows.remainInNightHold -> moved(Phase.AWAKE, "boundary")
                e.resumeBurstCorroborated -> moved(Phase.AWAKE, "resume")
                else -> {
                    val gentle = e.windows.nearScheduledWake &&
                        e.sustainedElevatedHr &&
                        ((e.stepsLast15Min ?: 0) >= 100 || growth >= 100)
                    val stepsOnly = strong &&
                        (e.droughtEstablished || e.windows.inLieIn)
                    when {
                        !gentle && !stepsOnly ->
                            Result(before.copy(wakeCandidateSinceMs = null), false, null)
                        before.wakeCandidateSinceMs == null ->
                            Result(before.copy(wakeCandidateSinceMs = e.nowMs), false, null)
                        e.nowMs - before.wakeCandidateSinceMs >=
                            e.minWakeHysteresisMinutes.toLong() * MINUTE_MS ->
                                moved(Phase.AWAKE,
                                    if (gentle) "hr_steps" else "steps")
                        else -> Result(before, false, null)
                    }
                }
            }
        }
    }
}
