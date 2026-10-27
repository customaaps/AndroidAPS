package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1ApsTypeBoundary
import app.aaps.plugins.aps.openAPSBoost.BoostV1CgmGateShadow
import app.aaps.plugins.aps.openAPSBoost.BoostV1DetermineBasalInvocation
import app.aaps.plugins.aps.openAPSBoost.BoostV1DetermineBasalReplay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostV1DetermineBasalReplayTest {
    private fun fixture(bg: Double = 110.0, noise: Int = 0) =
        BoostV1DetermineBasalInvocation.Inputs(
            base = BoostV1ApsTypeBoundary.Inputs(
                glucose = BoostV1CgmGateShadow.Input(
                    currentTimeMs = 1_800_000L, glucoseTimeMs = 1_500_000L,
                    bgMgDl = bg, noise = noise, flatBGsDetected = false
                ),
                currentTempRate = 0.5, currentTempMinutes = 30,
                profile = BoostV1ApsTypeBoundary.Profile(0.5, 2.0, 1.0, 3.0, 4.0, true),
                iob = listOf(BoostV1ApsTypeBoundary.IobPoint(1_500_000L, 0.2, 0.01))
            ),
            autosens = BoostV1DetermineBasalInvocation.Autosens(1.0),
            meal = BoostV1DetermineBasalInvocation.Meal(0.0, 0.0),
            microBolusAllowed = false,
            recentSmbVolume60Min = 0.0,
            cumulativeSmbCap60Min = 1.5,
            recentLowBg45Min = 999.0,
            timeSinceLastSmbMin = 720.0,
            postRescueTightRampCap = null
        )

    @Test fun replayGateNeverActs() {
        val report = BoostV1DetermineBasalReplay.evaluate(fixture())
        assertEquals(BoostV1DetermineBasalReplay.Decision.READY_FOR_REFERENCE_EXECUTION, report.decision)
        assertFalse(report.mayDoseInsulin)
        assertFalse(report.isReferenceCalculation)
        assertEquals(0.2, report.currentIob)
    }

    @Test fun rejectsSensorNoiseEarly() {
        val report = BoostV1DetermineBasalReplay.evaluate(fixture(noise = 3))
        assertEquals(BoostV1DetermineBasalReplay.Decision.CGM_EARLY_EXIT, report.decision)
    }

    @Test fun invalidSmbHistoryFailsClosed() {
        val report = BoostV1DetermineBasalReplay.evaluate(
            fixture().copy(recentSmbVolume60Min = Double.NaN)
        )
        assertEquals(BoostV1DetermineBasalReplay.Decision.INVALID_INPUT, report.decision)
    }

    @Test fun hypoGlucoseDoesNotBypassReplayOnlyFlag() {
        val report = BoostV1DetermineBasalReplay.evaluate(fixture(bg = 55.0))
        assertFalse(report.mayDoseInsulin)
    }
}
