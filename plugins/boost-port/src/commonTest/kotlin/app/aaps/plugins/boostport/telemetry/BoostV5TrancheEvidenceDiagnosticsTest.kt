package app.aaps.plugins.boostport.telemetry

import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoostV5TrancheEvidenceDiagnosticsTest {
    private val diag = BoostV5TrancheEvidenceDiagnostics
    private val minute = 60_000L

    private fun observation(
        atConfirm: Double = 110.0,
        current: Double = 160.0,
        previous: Double? = 130.0,
        peak: Double = 160.0,
    ) = BoostV5TrancheEvidenceDiagnostics.Observation(
        bgAtConfirmMgdl = atConfirm,
        latestBgMgdl = current,
        previousBgMgdl = previous,
        maximumBgSinceConfirmMgdl = peak,
    )

    @Test fun fiveMinutesIsBeforeHistoricDecisionWindow() {
        assertEquals(BoostV5TrancheEvidenceDiagnostics.Window.TOO_EARLY,
            diag.window(0L, 5 * minute))
    }

    @Test fun sevenAndAHalfMinutesIsFirstPermittedEvidenceInstant() {
        assertEquals(BoostV5TrancheEvidenceDiagnostics.Window.TOO_EARLY,
            diag.window(0L, 449_999L))
        assertEquals(BoostV5TrancheEvidenceDiagnostics.Window.EVIDENCE_WINDOW,
            diag.window(0L, 450_000L))
    }

    @Test fun ninePointNineNineOneMinuteCycleIsNotDeferredToNextCycle() {
        val elapsed = (9.991 * 60_000).toLong()
        assertEquals(BoostV5TrancheEvidenceDiagnostics.Window.EVIDENCE_WINDOW,
            diag.window(0L, elapsed))
    }

    @Test fun thirtyMinuteWindowIsInclusiveButLaterSamplesExpire() {
        assertEquals(BoostV5TrancheEvidenceDiagnostics.Window.EVIDENCE_WINDOW,
            diag.window(0L, 30 * minute))
        assertEquals(BoostV5TrancheEvidenceDiagnostics.Window.EXPIRED,
            diag.window(0L, 30 * minute + 1L))
    }

    @Test fun negativeOrReversedTimestampsAreInvalid() {
        assertEquals(BoostV5TrancheEvidenceDiagnostics.Window.INVALID,
            diag.window(-1L, 0L))
        assertEquals(BoostV5TrancheEvidenceDiagnostics.Window.INVALID,
            diag.window(minute, minute - 1L))
    }

    @Test fun sigmoidUsesAllSixLegacyTerms() {
        val obs = observation()
        val z = 2.48631 - 0.014480 * 110.0 - 0.003822 * 50.0 +
            0.138821 * 50.0 + 0.072499 * 30.0 - 0.018302 * 160.0
        val expected = 1.0 / (1.0 + exp(-z))
        val result = diag.probability(obs)
        assertNotNull(result)
        assertEquals(expected, result, 1e-12)
    }

    @Test fun weakerTrajectoryHasLowerProbabilityThanSustainedRise() {
        val steep = diag.probability(observation())!!
        val flat = diag.probability(observation(
            atConfirm = 180.0, previous = 181.0, current = 180.0, peak = 181.0
        ))!!
        assertTrue(steep > flat)
    }

    @Test fun missingPreviousBgUsesZeroCurrentSlope() {
        val obs = observation(previous = null)
        val p = diag.probability(obs)
        assertNotNull(p)
        val z = 2.48631 - 0.014480 * 110.0 - 0.003822 * 50.0 +
            0.138821 * 50.0 - 0.018302 * 160.0
        assertEquals(1.0 / (1.0 + exp(-z)), p, 1e-12)
    }

    @Test fun peakHistoryMayBeLowerThanCurrentWithoutBreakingMaxRule() {
        val lowerPeak = diag.probability(observation(peak = 120.0))
        val currentPeak = diag.probability(observation(peak = 160.0))
        assertEquals(currentPeak, lowerPeak)
    }

    @Test fun invalidMeasurementsAbstainInsteadOfGeneratingEvidence() {
        assertNull(diag.probability(observation(current = Double.NaN)))
        assertNull(diag.probability(observation(peak = Double.POSITIVE_INFINITY)))
        assertNull(diag.probability(observation(previous = Double.NEGATIVE_INFINITY)))
        assertNull(diag.probability(observation(atConfirm = -10.0)))
        assertNull(diag.probability(observation(current = 0.0)))
    }

    @Test fun finiteInputYieldsProbabilityInUnitInterval() {
        for (bg in listOf(70.0, 100.0, 140.0, 210.0, 300.0)) {
            val result = diag.probability(observation(current = bg, peak = bg))!!
            assertTrue(result in 0.0..1.0)
        }
    }
}
