package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StepCounterDiagnosticsTest {
    @Test fun missingSensorEventMeansUnknownNotZeroSteps() {
        val tracker = StepCounterDiagnostics()
        val snapshot = tracker.snapshot(1_000_000L)
        assertFalse(snapshot.feedLive)
        assertEquals(null, snapshot.lastEventMs)
        assertEquals(0L, snapshot.observedIncrementTotal)
    }

    @Test fun firstEventEstablishesBaselineWithoutCountingBootHistory() {
        val tracker = StepCounterDiagnostics()
        assertTrue(tracker.accept(8_000L, 100_000L))
        val snapshot = tracker.snapshot(100_000L)
        assertTrue(snapshot.feedLive)
        assertEquals(0L, snapshot.observedIncrementTotal)
        assertEquals(0, snapshot.discontinuities)
    }

    @Test fun reasonableIncrementsAccumulateAndFreshnessExpires() {
        val tracker = StepCounterDiagnostics(staleAfterMs = 1_000L)
        tracker.accept(100L, 20_000L)
        tracker.accept(125L, 20_500L)
        tracker.accept(130L, 21_000L)
        assertEquals(30L, tracker.snapshot(21_500L).observedIncrementTotal)
        assertTrue(tracker.snapshot(22_000L).feedLive)
        assertFalse(tracker.snapshot(22_001L).feedLive)
    }

    @Test fun resetRebaselinesWithoutInventingSteps() {
        val tracker = StepCounterDiagnostics()
        tracker.accept(1_000L, 10_000L)
        tracker.accept(1_025L, 11_000L)
        tracker.accept(4L, 12_000L)
        tracker.accept(14L, 13_000L)
        val snapshot = tracker.snapshot(13_000L)
        assertEquals(35L, snapshot.observedIncrementTotal)
        assertEquals(1, snapshot.discontinuities)
    }

    @Test fun implausibleJumpIsDiscardedAndNextEventUsesNewBaseline() {
        val tracker = StepCounterDiagnostics(maxPlausibleIncrement = 100L)
        tracker.accept(10L, 1_000L)
        tracker.accept(50_000L, 2_000L)
        tracker.accept(50_007L, 3_000L)
        val snapshot = tracker.snapshot(3_000L)
        assertEquals(7L, snapshot.observedIncrementTotal)
        assertEquals(1, snapshot.discontinuities)
    }

    @Test fun invalidInputAndClockRollbackDoNotRefreshFeed() {
        val tracker = StepCounterDiagnostics(staleAfterMs = 10L)
        tracker.accept(20L, 100L)
        assertFalse(tracker.accept(-1L, 101L))
        assertFalse(tracker.accept(25L, -1L))
        assertFalse(tracker.accept(25L, 99L))
        assertEquals(100L, tracker.snapshot(105L).lastEventMs)
        assertFalse(tracker.snapshot(111L).feedLive)
        assertFalse(tracker.snapshot(99L).feedLive)
        assertEquals(0L, tracker.snapshot(111L).observedIncrementTotal)
    }

    @Test fun overflowSaturatesRatherThanWrappingNegative() {
        val tracker = StepCounterDiagnostics(maxPlausibleIncrement = Long.MAX_VALUE)
        tracker.accept(0L, 1L)
        tracker.accept(Long.MAX_VALUE - 2L, 2L)
        tracker.accept(0L, 3L) // sensor reset; don't add the new baseline
        tracker.accept(20L, 4L) // must saturate rather than overflow
        assertEquals(Long.MAX_VALUE, tracker.snapshot(4L).observedIncrementTotal)
    }
}
