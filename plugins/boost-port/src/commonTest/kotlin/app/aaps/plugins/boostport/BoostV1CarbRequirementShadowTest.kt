package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1CarbRequirementShadow
import app.aaps.plugins.aps.openAPSBoost.BoostV1PredictionSafetyTimeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoostV1CarbRequirementShadowTest {
    private fun input() = BoostV1CarbRequirementShadow.Input(
        currentBasal = 1.0, sensitivity = 40.0, profileSensitivity = 50.0,
        carbRatio = 10.0, dynamicCarbRatio = 0.0, mealCarbs = 40.0,
        mealCob = 15.0, carbsReqThreshold = 5.0
    )

    private fun safety(minutes: Int = 30, undershoot: Double = 100.0) =
        BoostV1PredictionSafetyTimeline.Result(
            finding = BoostV1PredictionSafetyTimeline.Finding.READY,
            minutesAboveThreshold = minutes, glucoseUndershoot = undershoot
        )

    @Test fun preservesOriginalProfileCsfAndUnroundedZeroTempEffect() {
        // Original CSF = 50/10 = 5, zero-temp effect = 20, COB offset = 5.
        val r = BoostV1CarbRequirementShadow.inspect(input(), safety())
        assertEquals(BoostV1CarbRequirementShadow.Finding.READY, r.finding)
        assertEquals(5.0, r.carbSensitivityFactor)
        assertEquals(20.0, r.zeroTempEffectMgDl)
        assertEquals(5.0, r.cobOffsetGrams)
        assertEquals(11, r.calculatedCarbsGrams)
        assertTrue(r.wouldReportCarbsRequired)
        assertEquals(30, r.reportedWithinMinutes)
        assertFalse(r.canDeliverInsulin)
    }

    @Test fun dynamicCarbRatioOverridesProfileRatio() {
        val r = BoostV1CarbRequirementShadow.inspect(input().copy(dynamicCarbRatio = 20.0), safety())
        assertEquals(20.0, r.effectiveCarbRatio)
        assertEquals(2.5, r.carbSensitivityFactor)
        assertEquals(27, r.calculatedCarbsGrams)
    }

    @Test fun subtractsOnlyCobBeyondOneQuarterOfAnnouncedCarbs() {
        val r = BoostV1CarbRequirementShadow.inspect(input().copy(mealCob = 8.0), safety())
        assertEquals(0.0, r.cobOffsetGrams)
        assertEquals(16, r.calculatedCarbsGrams)
    }

    @Test fun noMealAndZeroBasalAreValidOfflineInputs() {
        val r = BoostV1CarbRequirementShadow.inspect(
            input().copy(currentBasal = 0.0, mealCarbs = 0.0, mealCob = 0.0), safety()
        )
        assertEquals(20, r.calculatedCarbsGrams)
        assertEquals(0.0, r.zeroTempEffectMgDl)
    }

    @Test fun reportUsesInclusiveFortyFiveMinuteAndCarbThresholdBoundaries() {
        val i = input().copy(currentBasal = 0.0, mealCob = 0.0, carbsReqThreshold = 20.0)
        assertTrue(BoostV1CarbRequirementShadow.inspect(i, safety(45)).wouldReportCarbsRequired)
        val late = BoostV1CarbRequirementShadow.inspect(i, safety(50))
        assertEquals(20, late.calculatedCarbsGrams)
        assertFalse(late.wouldReportCarbsRequired)
        assertNull(late.reportedWithinMinutes)
        assertFalse(BoostV1CarbRequirementShadow.inspect(
            i.copy(carbsReqThreshold = 21.0), safety(45)
        ).wouldReportCarbsRequired)
    }

    @Test fun usesSourceRoundingOnBothSidesOfZero() {
        val i = input().copy(currentBasal = 0.0, mealCarbs = 0.0, mealCob = 0.0)
        assertEquals(3, BoostV1CarbRequirementShadow.inspect(i, safety(0, 12.5)).calculatedCarbsGrams)
        assertEquals(-2, BoostV1CarbRequirementShadow.inspect(i, safety(0, -12.5)).calculatedCarbsGrams)
        assertEquals(-3, BoostV1CarbRequirementShadow.inspect(i, safety(0, -12.6)).calculatedCarbsGrams)
    }

    @Test fun negativeCarbBalanceIsRetainedWithoutAnAlert() {
        val r = BoostV1CarbRequirementShadow.inspect(input(), safety(30, -5.0))
        assertEquals(-10, r.calculatedCarbsGrams)
        assertFalse(r.wouldReportCarbsRequired)
    }

    @Test fun unavailableOrMalformedTimelineCannotSupplyCarbEvidence() {
        val timelines = listOf(
            BoostV1PredictionSafetyTimeline.Result(BoostV1PredictionSafetyTimeline.Finding.INVALID_INPUT),
            safety().copy(minutesAboveThreshold = null), safety(-5), safety(245), safety(31),
            safety().copy(glucoseUndershoot = null), safety(30, Double.NaN)
        )
        for (s in timelines) {
            val r = BoostV1CarbRequirementShadow.inspect(input(), s)
            assertEquals(BoostV1CarbRequirementShadow.Finding.INVALID_SAFETY_TIMELINE, r.finding)
            assertNull(r.calculatedCarbsGrams)
            assertFalse(r.wouldReportCarbsRequired)
        }
    }

    @Test fun invalidProfileAndOverflowFailClosed() {
        val inputs = listOf(
            input().copy(currentBasal = -1.0), input().copy(sensitivity = Double.NaN),
            input().copy(profileSensitivity = 0.0), input().copy(carbRatio = 0.0),
            input().copy(dynamicCarbRatio = -1.0), input().copy(mealCarbs = -1.0),
            input().copy(mealCob = Double.POSITIVE_INFINITY), input().copy(carbsReqThreshold = -1.0),
            input().copy(sensitivity = Double.MAX_VALUE),
            input().copy(profileSensitivity = Double.MIN_VALUE, dynamicCarbRatio = Double.MAX_VALUE)
        )
        for (i in inputs) {
            val r = BoostV1CarbRequirementShadow.inspect(i, safety())
            assertEquals(BoostV1CarbRequirementShadow.Finding.INVALID_INPUT, r.finding)
            assertNull(r.calculatedCarbsGrams)
        }
    }
}
