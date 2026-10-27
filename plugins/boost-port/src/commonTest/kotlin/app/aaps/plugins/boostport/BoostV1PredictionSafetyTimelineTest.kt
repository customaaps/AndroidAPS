package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1PredictionSafetyTimeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoostV1PredictionSafetyTimelineTest {
    private fun input() = BoostV1PredictionSafetyTimeline.Input(
        bg = 120.0, minBg = 90.0, lgsThreshold = 70.0,
        naiveEventualBg = 100.0, minGuardBg = 80.0,
        mealCob = 0.0, carbImpact = 0.0, remainingCarbImpactPeak = 0.0,
        cobPredictions = listOf(120, 110, 100),
        iobPredictions = listOf(120, 90, 89, 70, 69),
        maxDelta = 5.0, smbConditionMatched = true
    )

    @Test fun firstStrictCrossingUsesFiveMinuteIntervals() {
        val r = BoostV1PredictionSafetyTimeline.inspect(input())
        assertEquals(BoostV1PredictionSafetyTimeline.Finding.READY, r.finding)
        assertEquals(10, r.minutesAboveMinBg)
        assertEquals(20, r.minutesAboveThreshold)
        assertTrue(r.guardedSmbConditionMatched)
        assertFalse(r.mayDeliverInsulin)
    }

    @Test fun activeCobSelectsCobRatherThanIobHorizon() {
        for (i in listOf(
            input().copy(mealCob = 10.0, carbImpact = 1.0),
            input().copy(mealCob = 10.0, remainingCarbImpactPeak = 1.0)
        )) {
            val r = BoostV1PredictionSafetyTimeline.inspect(i)
            assertEquals(240, r.minutesAboveMinBg)
            assertEquals(240, r.minutesAboveThreshold)
        }
    }

    @Test fun cobWithoutCarbImpactUsesIobHorizon() {
        assertEquals(20, BoostV1PredictionSafetyTimeline.inspect(
            input().copy(mealCob = 10.0)
        ).minutesAboveThreshold)
    }

    @Test fun currentValueAlreadyBelowThresholdHasZeroMinuteHorizon() {
        val r = BoostV1PredictionSafetyTimeline.inspect(input().copy(iobPredictions = listOf(69)))
        assertEquals(0, r.minutesAboveMinBg)
        assertEquals(0, r.minutesAboveThreshold)
    }

    @Test fun lowGuardVetoIsStrictAndCannotBeOverriddenBySmbCondition() {
        val boundary = BoostV1PredictionSafetyTimeline.inspect(input().copy(minGuardBg = 70.0))
        assertFalse(boundary.lowGuardVeto)
        assertTrue(boundary.guardedSmbConditionMatched)
        val low = BoostV1PredictionSafetyTimeline.inspect(input().copy(minGuardBg = 69.9))
        assertTrue(low.lowGuardVeto)
        assertFalse(low.guardedSmbConditionMatched)
    }

    @Test fun rapidDeltaVetoStartsAboveThirtyPercentOfBg() {
        assertTrue(BoostV1PredictionSafetyTimeline.inspect(
            input().copy(maxDelta = 36.0)
        ).guardedSmbConditionMatched)
        val r = BoostV1PredictionSafetyTimeline.inspect(input().copy(maxDelta = 36.1))
        assertTrue(r.rapidDeltaVeto)
        assertFalse(r.guardedSmbConditionMatched)
    }

    @Test fun vetoesAreIndependentAndCannotEnableOriginallyBlockedSmb() {
        val both = BoostV1PredictionSafetyTimeline.inspect(input().copy(
            minGuardBg = 60.0, maxDelta = 40.0
        ))
        assertTrue(both.lowGuardVeto)
        assertTrue(both.rapidDeltaVeto)
        assertFalse(both.guardedSmbConditionMatched)
        assertFalse(BoostV1PredictionSafetyTimeline.inspect(
            input().copy(smbConditionMatched = false)
        ).guardedSmbConditionMatched)
    }

    @Test fun extremeNaiveLowUsesLowerGuardForUndershoot() {
        assertEquals(40.0, BoostV1PredictionSafetyTimeline.inspect(input().copy(
            naiveEventualBg = 35.0, minGuardBg = 30.0
        )).glucoseUndershoot)
        assertEquals(30.0, BoostV1PredictionSafetyTimeline.inspect(input().copy(
            naiveEventualBg = 40.0, minGuardBg = 30.0
        )).glucoseUndershoot)
    }

    @Test fun emptySelectedPredictionAndInvalidNumbersAreRejected() {
        for (i in listOf(
            input().copy(iobPredictions = emptyList()),
            input().copy(mealCob = 1.0, carbImpact = 1.0, cobPredictions = emptyList()),
            input().copy(maxDelta = Double.NaN),
            input().copy(lgsThreshold = 0.0)
        )) {
            val r = BoostV1PredictionSafetyTimeline.inspect(i)
            assertEquals(BoostV1PredictionSafetyTimeline.Finding.INVALID_INPUT, r.finding)
            assertFalse(r.guardedSmbConditionMatched)
        }
    }
}
