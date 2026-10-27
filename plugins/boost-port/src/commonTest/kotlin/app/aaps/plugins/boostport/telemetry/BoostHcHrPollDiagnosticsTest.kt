package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoostHcHrPollDiagnosticsTest {
    private val policy = BoostHcHrPollDiagnostics
    private val minute = 60_000L
    private val now = 100L * 60L * minute

    private fun inspect(
        state: BoostHcHrPollDiagnostics.State = BoostHcHrPollDiagnostics.State(),
        time: Long = now,
        enabled: Boolean = true,
        sdk: Boolean = true,
        granted: Boolean = true,
        interval: Int = 5,
    ) = policy.inspect(state, time, enabled, sdk, granted, interval)

    @Test fun enabledGrantedSdkYieldsTrailingEighteenHourWindow() {
        val result = inspect()
        assertEquals(BoostHcHrPollDiagnostics.Gate.WOULD_SWEEP, result.gate)
        assertEquals(now - policy.LOOKBACK_MS, result.requestedSinceMs)
        assertEquals(now, result.requestedUntilMs)
        assertEquals(now, result.nextState.lastPollStartedMs)
        assertTrue(result.nextState.inFlight)
    }

    @Test fun backdatedMorningBatchIsNotMissedByForwardOnlyMarker() {
        val previous = BoostHcHrPollDiagnostics.State(
            lastPollStartedMs = now - 5 * minute,
        )
        val result = inspect(previous) 
        assertEquals(BoostHcHrPollDiagnostics.Gate.WOULD_SWEEP, result.gate)
        assertEquals(now - policy.LOOKBACK_MS, result.requestedSinceMs)
        assertTrue(result.requestedSinceMs!! < previous.lastPollStartedMs)
    }

    @Test fun recentlyStartedPollIsThrottled() {
        val state = BoostHcHrPollDiagnostics.State(lastPollStartedMs = now - 4 * minute)
        val result = inspect(state)
        assertEquals(BoostHcHrPollDiagnostics.Gate.THROTTLED, result.gate)
        assertEquals(state, result.nextState)
        assertNull(result.requestedSinceMs)
    }

    @Test fun exactPollIntervalBoundaryIsEligible() {
        val state = BoostHcHrPollDiagnostics.State(lastPollStartedMs = now - 5 * minute)
        assertEquals(BoostHcHrPollDiagnostics.Gate.WOULD_SWEEP,
            inspect(state).gate)
    }

    @Test fun zeroConfiguredPollMinutesCoercesToOneAsInLegacy() {
        val last = BoostHcHrPollDiagnostics.State(lastPollStartedMs = now - minute)
        assertEquals(BoostHcHrPollDiagnostics.Gate.WOULD_SWEEP,
            inspect(last, interval = 0).gate)
    }

    @Test fun inFlightReadBlocksConcurrentPollRegardlessOfElapsedTime() {
        val state = BoostHcHrPollDiagnostics.State(
            lastPollStartedMs = now - 60 * minute, inFlight = true,
        )
        val result = inspect(state)
        assertEquals(BoostHcHrPollDiagnostics.Gate.IN_FLIGHT, result.gate)
        assertEquals(state, result.nextState)
    }

    @Test fun completionClearsOnlyTheInFlightFlag() {
        val first = inspect()
        val finished = policy.finish(first.nextState)
        assertFalse(finished.inFlight)
        assertEquals(now, finished.lastPollStartedMs)
        assertEquals(finished, policy.finish(finished))
        assertEquals(BoostHcHrPollDiagnostics.Gate.THROTTLED,
            inspect(finished, time = now + minute).gate)
    }

    @Test fun missingSdkAndPermissionAreNotRepresentedAsZeroHeartRate() {
        assertEquals(BoostHcHrPollDiagnostics.Gate.SDK_UNAVAILABLE,
            inspect(sdk = false).gate)
        assertEquals(BoostHcHrPollDiagnostics.Gate.PERMISSION_REQUIRED,
            inspect(granted = false).gate)
        assertEquals(BoostHcHrPollDiagnostics.Gate.DISABLED,
            inspect(enabled = false).gate)
    }

    @Test fun missingPermissionDoesNotAdvanceLastStartedMarker() {
        val state = BoostHcHrPollDiagnostics.State(lastPollStartedMs = now - 10 * minute)
        val result = inspect(state, granted = false)
        assertEquals(state, result.nextState)
        assertNull(result.requestedUntilMs)
    }

    @Test fun windowNeverBeginsBeforeUnixEpoch() {
        val result = inspect(time = 6 * minute)
        assertEquals(BoostHcHrPollDiagnostics.Gate.WOULD_SWEEP, result.gate)
        assertEquals(0L, result.requestedSinceMs)
    }

    @Test fun clockReversalAndInvalidStateAbstain() {
        val state = BoostHcHrPollDiagnostics.State(lastPollStartedMs = now + 1)
        assertEquals(BoostHcHrPollDiagnostics.Gate.INVALID_INPUT, inspect(state).gate)
        assertEquals(BoostHcHrPollDiagnostics.Gate.INVALID_INPUT,
            inspect(time = -1).gate)
        assertEquals(BoostHcHrPollDiagnostics.Gate.INVALID_INPUT,
            inspect(interval = 1441).gate)
    }

    @Test fun repeatedEvaluationOfInFlightStateCannotEmitSecondSweep() {
        val first = inspect()
        val next = inspect(first.nextState, time = now + minute)
        assertEquals(BoostHcHrPollDiagnostics.Gate.IN_FLIGHT, next.gate)
        assertNull(next.requestedSinceMs)
    }
}
