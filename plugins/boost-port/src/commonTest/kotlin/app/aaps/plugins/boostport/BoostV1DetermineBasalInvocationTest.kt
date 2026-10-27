package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1ApsTypeBoundary
import app.aaps.plugins.aps.openAPSBoost.BoostV1CgmGateShadow
import app.aaps.plugins.aps.openAPSBoost.BoostV1DetermineBasalInvocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostV1DetermineBasalInvocationTest {
    private fun fixture() = BoostV1DetermineBasalInvocation.Inputs(
        base = BoostV1ApsTypeBoundary.Inputs(
            glucose = BoostV1CgmGateShadow.Input(
                currentTimeMs = 1_800_000L, glucoseTimeMs = 1_500_000L,
                bgMgDl = 110.0, noise = 0, flatBGsDetected = false
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

    @Test fun completeSnapshotIsReplayOnly() {
        val result = BoostV1DetermineBasalInvocation.inspect(fixture())
        assertEquals(BoostV1DetermineBasalInvocation.Finding.READY_FOR_OFFLINE_REPLAY, result.finding)
        assertFalse(result.mayDispatchToPump)
        assertFalse(result.mayExecuteInsulinDosing)
    }

    @Test fun rejectInvalidSmbHistory() {
        val result = BoostV1DetermineBasalInvocation.inspect(
            fixture().copy(recentSmbVolume60Min = Double.NaN)
        )
        assertEquals(BoostV1DetermineBasalInvocation.Finding.INVALID_SMB_HISTORY, result.finding)
    }

    @Test fun rejectInvalidRescueCap() {
        val result = BoostV1DetermineBasalInvocation.inspect(
            fixture().copy(postRescueTightRampCap = Double.POSITIVE_INFINITY)
        )
        assertEquals(BoostV1DetermineBasalInvocation.Finding.INVALID_RESCUE_CONFIGURATION, result.finding)
    }

    @Test fun rejectInvalidAutosens() {
        val result = BoostV1DetermineBasalInvocation.inspect(
            fixture().copy(autosens = BoostV1DetermineBasalInvocation.Autosens(Double.NaN))
        )
        assertEquals(BoostV1DetermineBasalInvocation.Finding.INVALID_AUTOSENS, result.finding)
    }
}
