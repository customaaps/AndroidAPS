package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1ApsTypeBoundary
import app.aaps.plugins.aps.openAPSBoost.BoostV1CarbRequirementShadow
import app.aaps.plugins.aps.openAPSBoost.BoostV1CgmGateShadow
import app.aaps.plugins.aps.openAPSBoost.BoostV1DetermineBasalInvocation
import app.aaps.plugins.aps.openAPSBoost.BoostV1HighBgTargetShadow
import app.aaps.plugins.aps.openAPSBoost.BoostV1IsfShadowCalculation
import app.aaps.plugins.aps.openAPSBoost.BoostV1LowGlucoseThresholdShadow
import app.aaps.plugins.aps.openAPSBoost.BoostV1OfflineCalculationPipeline
import app.aaps.plugins.aps.openAPSBoost.BoostV1OfflineRtEnvelope
import app.aaps.plugins.aps.openAPSBoost.BoostV1PredictionArraysShadow
import app.aaps.plugins.aps.openAPSBoost.BoostV1PredictionGuardBlend
import app.aaps.plugins.aps.openAPSBoost.BoostV1PredictionSafetyTimeline
import app.aaps.plugins.aps.openAPSBoost.BoostV1SmbEligibilityShadow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BoostV1OfflineRtEnvelopeTest {
    private fun pipeline() = BoostV1OfflineCalculationPipeline.Inputs(
        invocation = BoostV1DetermineBasalInvocation.Inputs(
            base = BoostV1ApsTypeBoundary.Inputs(
                glucose = BoostV1CgmGateShadow.Input(1_800_000L, 1_500_000L, 110.0, 0, false),
                currentTempRate = 0.5, currentTempMinutes = 30,
                profile = BoostV1ApsTypeBoundary.Profile(0.5, 2.0, 1.0, 3.0, 4.0, true),
                iob = listOf(BoostV1ApsTypeBoundary.IobPoint(1_500_000L, 0.2, 0.0)),
                glucoseDynamics = BoostV1ApsTypeBoundary.GlucoseDynamics(0.0, 0.0, 0.0)
            ),
            autosens = BoostV1DetermineBasalInvocation.Autosens(1.0),
            meal = BoostV1DetermineBasalInvocation.Meal(0.0, 0.0),
            microBolusAllowed = false, recentSmbVolume60Min = 0.0,
            cumulativeSmbCap60Min = 1.5, recentLowBg45Min = 999.0,
            timeSinceLastSmbMin = 720.0, postRescueTightRampCap = null
        ),
        selectedSensitivity = BoostV1IsfShadowCalculation.Input(110.0, true, 180.0, 55.0, 110.0, 40.0, 1.0),
        prediction = BoostV1PredictionArraysShadow.Input(
            bg = 110.0, sens = 40.0, ci = 0.0, uci = 0.0, cid = 0.0, acid = 0.0,
            remainingCATimeHours = 3.0, remainingCIpeak = 0.0, slopeFromDeviations = 0.0,
            insulinDivisor = 55.0, normalTarget = 110.0, sensNormalTarget = 40.0,
            velocity = 1.0, bgCap = 180.0,
            ticks = listOf(BoostV1PredictionArraysShadow.Tick(0.0, 0.0))
        ),
        smbEligibility = BoostV1SmbEligibilityShadow.Input(
            false, false, false, 100.0, false, false, false, false, 0.0, 0.0
        ),
        lgsThreshold = BoostV1LowGlucoseThresholdShadow.Input(90.0, null, 0.0)
    )

    private fun guard() = BoostV1PredictionGuardBlend.Input(
        mealCarbs = 0.0, mealCob = 0.0, enableUam = false,
        carbImpactDuration = 0.0, remainingCarbImpactPeak = 0.0,
        iobPredBg = 110.0, cobPredBg = null, uamPredBg = null,
        minIobPredBg = 90.0, minCobPredBg = 999.0, minUamPredBg = 999.0,
        minZtGuardBg = 100.0, minCobGuardBg = 999.0, minUamGuardBg = 999.0,
        minIobGuardBg = 90.0, maxCobPredBg = 110.0,
        currentBg = 110.0, threshold = 65.0, targetBg = 100.0
    )

    private fun safety() = BoostV1PredictionSafetyTimeline.Input(
        bg = 110.0, minBg = 90.0, lgsThreshold = 65.0, naiveEventualBg = 102.0,
        minGuardBg = 90.0, mealCob = 0.0, carbImpact = 0.0, remainingCarbImpactPeak = 0.0,
        cobPredictions = listOf(110, 110), iobPredictions = listOf(110, 110),
        maxDelta = 0.0, smbConditionMatched = false
    )

    private fun carbs() = BoostV1CarbRequirementShadow.Input(0.5, 40.0, 50.0, 10.0, 0.0, 0.0, 0.0, 5.0)

    @Test fun integratesNoMealCarbBalanceWithoutCreatingSourceRt() {
        val r = BoostV1OfflineRtEnvelope.inspect(
            pipeline(), 50.0, predictionGuardInput = guard(),
            predictionSafetyInput = safety(), carbRequirementInput = carbs()
        )
        assertEquals(BoostV1OfflineRtEnvelope.Finding.SNAPSHOT_READY, r.finding)
        val c = assertNotNull(r.carbRequirement)
        assertEquals(-23, c.calculatedCarbsGrams)
        assertFalse(c.wouldReportCarbsRequired)
        assertFalse(c.canDeliverInsulin)
        assertFalse(r.sourceEquivalentRtAvailable)
        assertFalse(r.eligibleForPumpDispatch)
    }

    @Test fun optionalCarbStageDoesNotChangePreviousSnapshots() {
        val r = BoostV1OfflineRtEnvelope.inspect(pipeline(), 50.0)
        assertEquals(BoostV1OfflineRtEnvelope.Finding.SNAPSHOT_READY, r.finding)
        assertNull(r.carbRequirement)
    }

    @Test fun carbStageRequiresSuccessfulPredictionSafetyStage() {
        val r = BoostV1OfflineRtEnvelope.inspect(pipeline(), 50.0, carbRequirementInput = carbs())
        assertEquals(BoostV1OfflineRtEnvelope.Finding.INVALID_CARB_REQUIREMENT, r.finding)
        assertNull(r.carbRequirement)
    }

    @Test fun carbInputsFromAnotherCycleAreRejected() {
        for (i in listOf(carbs().copy(currentBasal = 1.0), carbs().copy(sensitivity = 50.0),
            carbs().copy(profileSensitivity = 40.0), carbs().copy(mealCarbs = 10.0),
            carbs().copy(mealCob = 1.0), carbs().copy(carbRatio = 0.0))) {
            val r = BoostV1OfflineRtEnvelope.inspect(
                pipeline(), 50.0, predictionGuardInput = guard(),
                predictionSafetyInput = safety(), carbRequirementInput = i
            )
            assertEquals(BoostV1OfflineRtEnvelope.Finding.INVALID_CARB_REQUIREMENT, r.finding)
            assertNull(r.carbRequirement)
        }
    }

    @Test fun mismatchedPredictionImpactOrArraysAreRejectedBeforeCarbStage() {
        for (s in listOf(safety().copy(carbImpact = 1.0), safety().copy(remainingCarbImpactPeak = 1.0),
            safety().copy(iobPredictions = listOf(110, 60)))) {
            val r = BoostV1OfflineRtEnvelope.inspect(
                pipeline(), 50.0, predictionGuardInput = guard(),
                predictionSafetyInput = s, carbRequirementInput = carbs()
            )
            assertEquals(BoostV1OfflineRtEnvelope.Finding.INVALID_SAFETY_TIMELINE, r.finding)
            assertNull(r.carbRequirement)
        }
    }

    @Test fun predictionHorizonMustUseAdjustedMinimumTarget() {
        val target = BoostV1HighBgTargetShadow.Input(110.0, 95.0, 100.0, 120.0, 102.0, 102.0, false, false)
        val r = BoostV1OfflineRtEnvelope.inspect(
            pipeline(), 50.0, highBgTarget = target, predictionGuardInput = guard(),
            predictionSafetyInput = safety(), carbRequirementInput = carbs()
        )
        assertEquals(BoostV1OfflineRtEnvelope.Finding.INVALID_SAFETY_TIMELINE, r.finding)
    }
}
