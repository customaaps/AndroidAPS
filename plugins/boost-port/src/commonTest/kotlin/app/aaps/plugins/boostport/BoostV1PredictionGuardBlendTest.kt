package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1PredictionGuardBlend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Source: 091026 DetermineBasalBoost, minPredBG branch (lines 919–982). */
class BoostV1PredictionGuardBlendTest {
    private fun noCarbs() = BoostV1PredictionGuardBlend.Input(
        mealCarbs = 0.0, mealCob = 0.0, enableUam = false,
        carbImpactDuration = 0.0, remainingCarbImpactPeak = 0.0,
        iobPredBg = 140.0, cobPredBg = null, uamPredBg = null,
        minIobPredBg = 100.0, minCobPredBg = 999.0, minUamPredBg = 999.0,
        minZtGuardBg = 90.0, minCobGuardBg = 999.0,
        minUamGuardBg = 999.0, minIobGuardBg = 85.0,
        maxCobPredBg = 0.0, currentBg = 120.0, threshold = 70.0, targetBg = 100.0
    )

    @Test fun noCarbsAndNoUamUsesIobForecastAndGuard() {
        val r = assertNotNull(BoostV1PredictionGuardBlend.inspect(noCarbs()))
        assertEquals(140.0, r.averagePredBg)
        assertEquals(85.0, r.minGuardBg)
        assertEquals(100.0, r.minPredBg)
        assertFalse(r.mayDispatchToPump)
    }

    @Test fun noCarbsWithUamUsesItsForecastWithoutACobFraction() {
        val r = assertNotNull(BoostV1PredictionGuardBlend.inspect(noCarbs().copy(
            enableUam = true, uamPredBg = 120.0,
            minUamPredBg = 90.0, minUamGuardBg = 80.0, minZtGuardBg = 100.0
        )))
        assertEquals(130.0, r.averagePredBg)
        assertEquals(80.0, r.minGuardBg)
        assertEquals(95.0, r.minZtUamPredBg)
        assertEquals(100.0, r.minPredBg)
    }

    @Test fun noCarbsDoesNotManufactureFractionWhenBothPredictionsArePresent() {
        assertNull(BoostV1PredictionGuardBlend.inspect(noCarbs().copy(
            enableUam = true, cobPredBg = 120.0, uamPredBg = 100.0,
            minCobPredBg = 100.0, minUamPredBg = 90.0
        )))
    }

    @Test fun residualCarbImpactRequiringFractionFailsClosedWithoutCarbs() {
        for (input in listOf(
            noCarbs().copy(enableUam = true, carbImpactDuration = 1.0),
            noCarbs().copy(enableUam = true, remainingCarbImpactPeak = 1.0)
        )) assertNull(BoostV1PredictionGuardBlend.inspect(input))
    }

    @Test fun cobWithoutCarbsIsInconsistent() {
        assertNull(BoostV1PredictionGuardBlend.inspect(noCarbs().copy(mealCob = 1.0)))
    }

    @Test fun sourceRoundsPositiveHalfUpRatherThanToEven() {
        val r = assertNotNull(BoostV1PredictionGuardBlend.inspect(noCarbs().copy(
            iobPredBg = 140.5, minIobPredBg = 100.5, minIobGuardBg = 84.5
        )))
        assertEquals(141.0, r.averagePredBg)
        assertEquals(101.0, r.minPredBg)
        assertEquals(85.0, r.minGuardBg)
    }

    @Test fun sourceRoundsNegativeHalfTowardPositiveInfinity() {
        val r = assertNotNull(BoostV1PredictionGuardBlend.inspect(noCarbs().copy(
            minIobGuardBg = -85.5
        )))
        assertEquals(-85.0, r.minGuardBg)
    }

    @Test fun valueImmediatelyBelowHalfDoesNotRoundUp() {
        val r = assertNotNull(BoostV1PredictionGuardBlend.inspect(noCarbs().copy(
            minIobGuardBg = 0.49999999999999994
        )))
        assertEquals(0.0, r.minGuardBg)
    }

    @Test fun bothCarbForecastsAreWeightedByRemainingFraction() {
        val r = assertNotNull(BoostV1PredictionGuardBlend.inspect(noCarbs().copy(
            mealCarbs = 40.0, mealCob = 10.0, enableUam = true,
            carbImpactDuration = 1.0, cobPredBg = 180.0, uamPredBg = 120.0,
            minCobPredBg = 110.0, minUamPredBg = 90.0,
            minCobGuardBg = 80.0, minUamGuardBg = 60.0, minZtGuardBg = 100.0
        )))
        assertEquals(135.0, r.averagePredBg)
        assertEquals(65.0, r.minGuardBg)
        assertEquals(95.0, r.minZtUamPredBg)
        assertEquals(110.0, r.minPredBg)
    }

    @Test fun cobOnlyUsesIobCobAverageAndCobGuard() {
        val r = assertNotNull(BoostV1PredictionGuardBlend.inspect(noCarbs().copy(
            mealCarbs = 40.0, mealCob = 10.0, carbImpactDuration = 1.0,
            cobPredBg = 181.0, minCobPredBg = 110.0, minCobGuardBg = 80.5
        )))
        assertEquals(161.0, r.averagePredBg)
        assertEquals(81.0, r.minGuardBg)
        assertEquals(110.0, r.minPredBg)
    }

    @Test fun carbsWithoutAnyCarbForecastFallBackToGuardWhenUamDisabled() {
        val r = assertNotNull(BoostV1PredictionGuardBlend.inspect(noCarbs().copy(
            mealCarbs = 40.0
        )))
        assertEquals(85.0, r.minPredBg)
    }

    @Test fun zeroTempBelowThresholdAveragesWithUam() {
        val r = assertNotNull(BoostV1PredictionGuardBlend.inspect(noCarbs().copy(
            minUamPredBg = 101.0, uamPredBg = 120.0, minZtGuardBg = 60.0
        )))
        assertEquals(81.0, r.minZtUamPredBg)
    }

    @Test fun zeroTempBetweenThresholdAndTargetBlendsWithUam() {
        val r = assertNotNull(BoostV1PredictionGuardBlend.inspect(noCarbs().copy(
            minUamPredBg = 100.0, uamPredBg = 120.0, minZtGuardBg = 85.0
        )))
        assertEquals(96.0, r.minZtUamPredBg)
    }

    @Test fun averageAndRisingCobLimitMinimumPrediction() {
        val r = assertNotNull(BoostV1PredictionGuardBlend.inspect(noCarbs().copy(
            iobPredBg = 140.0, minIobPredBg = 180.0, maxCobPredBg = 130.0
        )))
        assertEquals(130.0, r.minPredBg)
    }

    @Test fun missingUsedForecastAndNonFiniteInputsAreRejected() {
        assertNull(BoostV1PredictionGuardBlend.inspect(noCarbs().copy(
            minUamPredBg = 100.0, uamPredBg = null
        )))
        assertNull(BoostV1PredictionGuardBlend.inspect(noCarbs().copy(
            minIobGuardBg = Double.NaN
        )))
    }
}
