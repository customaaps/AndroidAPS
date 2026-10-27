package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoostV5HistoryGapDiagnosticsTest {
    private val d = BoostV5HistoryGapDiagnostics
    private val now = 2_000_000_000_000L
    private val thin = BoostV5HistoryGapDiagnostics.History(1, 200, 10)
    private val sufficient = BoostV5HistoryGapDiagnostics.History(7, 1500, 50)

    private fun inspect(
        history: BoostV5HistoryGapDiagnostics.History = thin,
        configured: Boolean = true,
        time: Long = now,
        state: BoostV5HistoryGapDiagnostics.State = BoostV5HistoryGapDiagnostics.State(),
    ) = d.inspect(history, configured, time, "test", state)

    @Test fun sufficientHistoryDoesNotSuggestBackfill() {
        val result = inspect(history = sufficient)
        assertFalse(result.wouldRequestBackfill)
        assertNull(result.summary)
        assertEquals(now, result.nextState?.firstSeenMs)
    }

    @Test fun genuineThinHistoryWouldSuggestOnlyBoundedBackfill() {
        val result = inspect()
        assertTrue(result.wouldRequestBackfill)
        assertTrue(result.summary!!.startsWith("requested:14d,attempt=1/3"))
        assertEquals(1, result.nextState?.attempts)
        assertEquals(200, result.nextState?.preBgReadings)
        assertEquals(10, result.nextState?.preTreatments)
        assertEquals(now, result.nextState?.firstSeenMs)
    }

    @Test fun noConfiguredNightscoutDoesNotSpendAnAttempt() {
        val result = inspect(configured = false)
        assertFalse(result.wouldRequestBackfill)
        assertTrue(result.summary!!.startsWith("skipped:ns-unavailable"))
        assertEquals(0, result.nextState?.attempts)
    }

    @Test fun retryCooldownPreventsAnotherRequestAtFiveHours() {
        val first = inspect().nextState!!
        val second = inspect(time = now + 5L * 3_600_000L, state = first)
        assertFalse(second.wouldRequestBackfill)
        assertNull(second.summary)
    }

    @Test fun retryBecomesEligibleAtSixHours() {
        val first = inspect().nextState!!
        val second = inspect(time = now + d.RETRY_COOLDOWN_MS, state = first)
        assertTrue(second.wouldRequestBackfill)
        assertEquals(2, second.nextState?.attempts)
    }

    @Test fun noMoreThanThreeAttemptsAreSuggested() {
        val s = BoostV5HistoryGapDiagnostics.State(
            attempts = 3,
            lastAttemptMs = now - d.RETRY_COOLDOWN_MS,
            firstSeenMs = now - 12L * 3_600_000L,
        )
        val decision = inspect(state = s)
        assertFalse(decision.wouldRequestBackfill)
        assertTrue(decision.summary!!.startsWith("exhausted:3"))
    }

    @Test fun thinHistoryOutsideFortyEightHourWindowNeverRequestsBackfill() {
        val s = BoostV5HistoryGapDiagnostics.State(firstSeenMs = now - 49L * 3_600_000L)
        val decision = inspect(state = s)
        assertFalse(decision.wouldRequestBackfill)
        assertTrue(decision.summary!!.startsWith("skipped:outside-new-install-window"))
    }

    @Test fun completingHistoryReportsRepairedRowsAndResetsAttempts() {
        val first = inspect().nextState!!
        val improved = BoostV5HistoryGapDiagnostics.History(7, 2000, 70)
        val result = inspect(history = improved, time = now + d.RETRY_COOLDOWN_MS, state = first)
        assertFalse(result.wouldRequestBackfill)
        assertTrue(result.summary!!.contains("treatments=+60,bg=+1800"))
        assertEquals(0, result.nextState?.attempts)
        assertEquals(now, result.nextState?.firstSeenMs)
    }

    @Test fun revertedClockCannotResetCooldownOrAuthorizeBackfill() {
        val first = inspect().nextState!!
        val earlier = inspect(time = now - 1L, state = first)
        assertFalse(earlier.wouldRequestBackfill)
        assertNull(earlier.nextState)
    }

    @Test fun invalidCountsOrTimesFailClosed() {
        assertFalse(inspect(
            history = BoostV5HistoryGapDiagnostics.History(-1, 0, 0)
        ).wouldRequestBackfill)
        assertFalse(inspect(time = -1L).wouldRequestBackfill)
        assertFalse(inspect(
            state = BoostV5HistoryGapDiagnostics.State(attempts = -1)
        ).wouldRequestBackfill)
    }

    @Test fun noNetworkOperationIsPresentInThePureDecisionResult() {
        val result = inspect()
        assertEquals(true, result.wouldRequestBackfill)
        assertTrue(result.summary!!.contains("days=1/7,bg=200/1500,tr=10/50"))
    }
}
