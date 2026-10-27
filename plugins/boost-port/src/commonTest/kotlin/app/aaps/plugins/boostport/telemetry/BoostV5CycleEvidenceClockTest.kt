package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoostV5CycleEvidenceClockTest {
    private val clock = BoostV5CycleEvidenceClock
    private val minute = 60_000L
    private val start = 1_700_000_000_000L

    @Test fun missingMlTicksOnlyOnFourMinuteCadence() {
        val first = clock.advance(BoostV5CycleEvidenceClock.State(), start, null, 0.4)!!
        val oneMin = clock.advance(first.state, start + minute, null, 0.4)!!
        val fourMin = clock.advance(oneMin.state, start + 4 * minute, null, 0.4)!!
        assertEquals(1, first.state.missingMlTicks)
        assertEquals(1, oneMin.state.missingMlTicks)
        assertEquals(2, fourMin.state.missingMlTicks)
    }

    @Test fun repeatedInvocationAtSameTimestampCannotInflateMissingMlCount() {
        val initial = clock.advance(BoostV5CycleEvidenceClock.State(), start, null, 0.4)!!
        val again = clock.advance(initial.state, start, null, 0.4)!!
        assertEquals(1, again.state.missingMlTicks)
    }

    @Test fun threeDistinctFourMinuteTicksEnableOnlyDiagnosticReweighting() {
        val first = clock.advance(BoostV5CycleEvidenceClock.State(), start, null, 0.4)!!
        val second = clock.advance(first.state, start + 4 * minute, null, 0.4)!!
        val third = clock.advance(second.state, start + 8 * minute, null, 0.4)!!
        assertFalse(second.wouldRenormalizeMissingMl)
        assertTrue(third.wouldRenormalizeMissingMl)
    }

    @Test fun availableMlResetsCounterAndDisablesMissingMlRenormalization() {
        val missing = clock.advance(BoostV5CycleEvidenceClock.State(), start, null, 0.3)!!
        val restored = clock.advance(missing.state, start + 4 * minute, 0.8, 0.3)!!
        assertEquals(0, restored.state.missingMlTicks)
        assertFalse(restored.wouldRenormalizeMissingMl)
    }

    @Test fun continuousStrongScoreNeedsFourMinutesBeforeReady() {
        val first = clock.advance(BoostV5CycleEvidenceClock.State(), start, 0.6, 0.7)!!
        val second = clock.advance(first.state, start + minute, 0.6, 0.7)!!
        val ready = clock.advance(second.state, start + 4 * minute, 0.6, 0.7)!!
        assertFalse(first.sustainedScoreReady)
        assertFalse(second.sustainedScoreReady)
        assertTrue(ready.sustainedScoreReady)
        assertEquals(start, ready.state.scoreReadySinceMs)
    }

    @Test fun repeatedStrongScoreCallDoesNotOpenEarlyConfirmReadiness() {
        val first = clock.advance(BoostV5CycleEvidenceClock.State(), start, 0.5, 0.8)!!
        val again = clock.advance(first.state, start, 0.5, 0.8)!!
        assertFalse(again.sustainedScoreReady)
    }

    @Test fun scoreBelowThresholdEndsStrongScoreRun() {
        val first = clock.advance(BoostV5CycleEvidenceClock.State(), start, 0.5, 0.8)!!
        val low = clock.advance(first.state, start + 2 * minute, 0.5, 0.5)!!
        val resume = clock.advance(low.state, start + 5 * minute, 0.5, 0.8)!!
        assertEquals(0L, low.state.scoreReadySinceMs)
        assertFalse(resume.sustainedScoreReady)
        assertEquals(start + 5 * minute, resume.state.scoreReadySinceMs)
    }

    @Test fun resetBreaksOldScoreEvidenceEvenWithHighLastScore() {
        val first = clock.advance(BoostV5CycleEvidenceClock.State(), start, 0.5, 0.8)!!
        val reset = clock.advance(first.state, start + 5 * minute, 0.5, 0.8,
            stateReset = true)!!
        assertFalse(reset.sustainedScoreReady)
        assertEquals(start + 5 * minute, reset.state.scoreReadySinceMs)
    }

    @Test fun noClockModeUsesLegacyPreviousInvocationScore() {
        val first = clock.advance(BoostV5CycleEvidenceClock.State(), 0L, 0.4, 0.8)!!
        val second = clock.advance(first.state, 0L, 0.4, 0.8)!!
        assertFalse(first.sustainedScoreReady)
        assertTrue(second.sustainedScoreReady)
        assertEquals(0L, second.state.scoreReadySinceMs)
    }

    @Test fun legacyPreferenceColdStartDoesNotRestoreEarlyConfirmEvidence() {
        val restored = clock.restoredFromPersistedNullStreak(2)!!
        assertEquals(2, restored.missingMlTicks)
        assertNull(restored.lastCycleScore)
        assertEquals(0L, restored.scoreReadySinceMs)
        assertEquals(0L, restored.missingMlTickAtMs)
        assertNull(clock.restoredFromPersistedNullStreak(-1))
    }

    @Test fun timestampGoingBackwardsFailsClosed() {
        val state = clock.advance(BoostV5CycleEvidenceClock.State(), start, null, 0.8)!!.state
        assertNull(clock.advance(state, start - 1L, null, 0.8))
    }

    @Test fun malformedScoreAndMlInputsFailClosed() {
        assertNull(clock.advance(BoostV5CycleEvidenceClock.State(), start, 0.3, Double.NaN))
        assertNull(clock.advance(BoostV5CycleEvidenceClock.State(), start, Double.POSITIVE_INFINITY, 0.8))
        assertNull(clock.advance(BoostV5CycleEvidenceClock.State(), start, 1.5, 0.8))
        assertNull(clock.advance(BoostV5CycleEvidenceClock.State(), -1L, null, 0.8))
        assertNull(clock.advance(BoostV5CycleEvidenceClock.State(), start, null, 1.1))
    }

    @Test fun overflowedNullCountFailsClosedWithoutWrapping() {
        val old = BoostV5CycleEvidenceClock.State(missingMlTicks = Int.MAX_VALUE,
            missingMlTickAtMs = start - 4 * minute)
        assertNull(clock.advance(old, start, null, 0.4))
    }

    @Test fun exactScoreThresholdStartsClockAtThatInstant() {
        val first = clock.advance(BoostV5CycleEvidenceClock.State(), start, 0.4, clock.CONFIRM_SCORE)!!
        assertEquals(start, first.state.scoreReadySinceMs)
        val next = clock.advance(first.state, start + 4 * minute, 0.4,
            clock.CONFIRM_SCORE)!!
        assertTrue(next.sustainedScoreReady)
    }
}
