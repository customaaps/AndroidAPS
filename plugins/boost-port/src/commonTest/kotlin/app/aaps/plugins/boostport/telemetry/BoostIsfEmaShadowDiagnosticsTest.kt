package app.aaps.plugins.boostport.telemetry

import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoostIsfEmaShadowDiagnosticsTest {
    private val d = BoostIsfEmaShadowDiagnostics
    private val now = 2_000_000_000_000L
    private val day = 86_400_000L

    private fun compare(
        tdd24: Double? = 50.0,
        tdd7: Double? = 25.0,
        nowMs: Long = now,
        state: BoostIsfEmaShadowDiagnostics.State = BoostIsfEmaShadowDiagnostics.State(),
        min: Double = 0.5,
        max: Double = 2.0,
    ) = d.compare(tdd24, tdd7, min, max, nowMs, state)

    @Test fun coldStartUsesNeutralEmaRatherThanApplyingRawSensitivity() {
        val result = compare()!!
        assertEquals(2.0, result.originalRawRatio)
        assertEquals(0.0, result.warmupFraction)
        assertEquals(1.0, result.warmedRatio)
        assertEquals(1.0, result.shadowRatio)
        assertEquals(now, result.nextState.firstSeenMs)
        assertEquals(now, result.nextState.lastUpdateMs)
    }

    @Test fun warmupInterpolatesOverFiveDays() {
        val seeded = BoostIsfEmaShadowDiagnostics.State(firstSeenMs = now - 2 * day)
        val result = compare(state = seeded)!!
        assertEquals(0.4, result.warmupFraction, 1e-9)
        assertEquals(1.4, result.warmedRatio, 1e-9)
        assertEquals(1.4, result.emaUnclamped, 1e-9)
    }

    @Test fun warmupSaturatesAfterFiveDays() {
        val seeded = BoostIsfEmaShadowDiagnostics.State(firstSeenMs = now - 12 * day)
        assertEquals(1.0, compare(state = seeded)!!.warmupFraction)
    }

    @Test fun fullEmaUpdateUsesActualElapsedTime() {
        val seeded = BoostIsfEmaShadowDiagnostics.State(
            ema = 1.0,
            lastUpdateMs = now - d.TAU_MS,
            firstSeenMs = now - 10 * day,
        )
        val result = compare(state = seeded)!!
        val expected = 1.0 + (1.0 - exp(-1.0)) * (2.0 - 1.0)
        assertEquals(expected, result.emaUnclamped, 1e-10)
        assertEquals(expected, result.shadowRatio, 1e-10)
    }

    @Test fun sameTimestampDoesNotAdvanceEma() {
        val seeded = BoostIsfEmaShadowDiagnostics.State(
            ema = 1.3, lastUpdateMs = now, firstSeenMs = now - 10 * day,
        )
        assertEquals(1.3, compare(state = seeded)!!.emaUnclamped, 1e-10)
    }

    @Test fun originalRawRatioAndFinalRatioAreClamped() {
        val result = compare(tdd24 = 500.0, tdd7 = 10.0,
            state = BoostIsfEmaShadowDiagnostics.State(firstSeenMs = now - 10 * day),
            max = 1.5)!!
        assertEquals(1.5, result.originalRawRatio)
        assertEquals(1.5, result.shadowRatio)
    }

    @Test fun zeroAndNonFiniteTddAbortComparison() {
        assertNull(compare(tdd24 = 0.0))
        assertNull(compare(tdd7 = null))
        assertNull(compare(tdd24 = Double.NaN))
        assertNull(compare(tdd7 = Double.POSITIVE_INFINITY))
        assertNull(compare(tdd24 = Double.NEGATIVE_INFINITY))
    }

    @Test fun malformedAutosensRangeAbortsComparison() {
        assertNull(compare(min = 0.0))
        assertNull(compare(min = 1.5, max = 0.5))
        assertNull(compare(max = Double.NaN))
    }

    @Test fun reversedTimeAndInvalidRestoredStateAbortComparison() {
        assertNull(compare(nowMs = -1L))
        assertNull(compare(state = BoostIsfEmaShadowDiagnostics.State(lastUpdateMs = now + 1L)))
        assertNull(compare(state = BoostIsfEmaShadowDiagnostics.State(firstSeenMs = now + 1L)))
        assertNull(compare(state = BoostIsfEmaShadowDiagnostics.State(ema = Double.NaN)))
    }

    @Test fun returnedNextStateIsPureAndTracksSubsequentCycles() {
        val first = compare()!!
        val second = compare(
            nowMs = now + d.TAU_MS,
            state = first.nextState,
        )!!
        assertEquals(now, first.nextState.firstSeenMs)
        assertEquals(now, second.nextState.firstSeenMs)
        assertEquals(now + d.TAU_MS, second.nextState.lastUpdateMs)
        assertEquals(1.0, first.nextState.ema)
    }
}
