package app.aaps.plugins.aps.openAPSBoost.activity

/** Pure, exact source 6.2 gate and boundary-exit signals. */
internal object BoostEligibilityPolicy {
internal fun boostGateOpen(nightSleepPeriod: Boolean, inNightWindow: Boolean, v6Active: Boolean, detectorSleeping: Boolean): Boolean =
    !nightSleepPeriod && !inNightWindow && !(v6Active && detectorSleeping)

/** The sleep signals [calculateBoostActivity] uses, after the boundary-exit hold. */
internal data class SleepSignals(
    val detectorSleeping: Boolean,
    val detectorAsleep: Boolean,
    val nightSleepPeriod: Boolean,
    val boundaryHold: Boolean
)

/** Minutes the sleep exclusions are held after a boundary exit: the detector's sleep hysteresis
 *  plus one five-minute cycle, the shortest time in which it can re-enter SLEEPING. */
internal fun boundaryExitHoldMin(sleepHysteresisMin: Int): Int = sleepHysteresisMin.coerceAtLeast(0) + 5

/**
 * Sleep signals with the boundary-exit hold (2026-10-08, audit #18). The detector can enter
 * SLEEPING up to 90 min before the night window opens but holds it only from the window start,
 * so a pre-window sleep is ended by the boundary rule on the next cycle and re-entered after the
 * hysteresis. Each such exit left one cycle reading AWAKE, which opened the INACTIVE raise and,
 * for V1 users with sleep-driven night mode, the Boost gate. The same rule ends the lie-in in
 * the morning; holding there delays the gate by one hysteresis period, which is the
 * conservative direction. For [holdMin] minutes after a boundary exit the user is treated as
 * SLEEPING for the gate and the INACTIVE exclusion, and the night/sleep period is held where it
 * was sleep-driven (night mode and auto-by-sleep both on). Genuine wakes (steps, HR, resume)
 * are not held. The detector itself is unchanged.
 */
internal fun sleepSignals(
    state: SleepStateDetector.SleepState,
    nightSleepPeriodRaw: Boolean,
    nightModeEnabled: Boolean,
    autoBySleep: Boolean,
    nowMs: Long,
    lastBoundaryExitMs: Long?,
    holdMin: Int
): SleepSignals {
    val sleeping = state == SleepStateDetector.SleepState.SLEEPING
    val hold = !sleeping && lastBoundaryExitMs != null &&
        nowMs >= lastBoundaryExitMs && nowMs - lastBoundaryExitMs < holdMin * 60_000L
    return SleepSignals(
        detectorSleeping = sleeping || hold,
        detectorAsleep = sleeping || state == SleepStateDetector.SleepState.PRE_SLEEP || hold,
        nightSleepPeriod = nightSleepPeriodRaw || (hold && nightModeEnabled && autoBySleep),
        boundaryHold = hold
    )
}
}
