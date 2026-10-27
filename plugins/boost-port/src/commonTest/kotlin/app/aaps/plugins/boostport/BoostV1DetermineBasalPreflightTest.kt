package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1ApsTypeBoundary
import app.aaps.plugins.aps.openAPSBoost.BoostV1CgmGateShadow
import app.aaps.plugins.aps.openAPSBoost.BoostV1DetermineBasalPreflight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostV1DetermineBasalPreflightTest {
    private fun fixture(bg: Double = 110.0, ageMinutes: Long = 5L, noise: Int = 0) =
        BoostV1ApsTypeBoundary.Inputs(
            glucose = BoostV1CgmGateShadow.Input(
                currentTimeMs = 1_800_000L,
                glucoseTimeMs = 1_800_000L - ageMinutes * 60_000L,
                bgMgDl = bg,
                noise = noise,
                flatBGsDetected = false
            ),
            currentTempRate = 0.5,
            currentTempMinutes = 20,
            profile = BoostV1ApsTypeBoundary.Profile(
                currentBasal = 0.5, maxBasal = 2.0, maxDailyBasal = 1.0,
                maxDailySafetyMultiplier = 3.0, currentBasalSafetyMultiplier = 4.0,
                skipNeutralTemps = true
            ),
            iob = listOf(BoostV1ApsTypeBoundary.IobPoint(1_500_000L, 0.3, 0.01))
        )

    @Test fun usableCgmIsReplayOnly() {
        val result = BoostV1DetermineBasalPreflight.inspect(fixture())
        assertEquals(BoostV1DetermineBasalPreflight.State.READY_FOR_REPLAY, result.state)
        assertFalse(result.mayCalculateDose)
        assertFalse(result.mayDispatchToPump)
    }

    @Test fun staleCgmIsNotReplayReady() {
        assertEquals(
            BoostV1DetermineBasalPreflight.State.STALE_CGM,
            BoostV1DetermineBasalPreflight.inspect(fixture(ageMinutes = 14)).state
        )
    }

    @Test fun unreliableCgmIsNotReplayReady() {
        assertEquals(
            BoostV1DetermineBasalPreflight.State.UNRELIABLE_CGM,
            BoostV1DetermineBasalPreflight.inspect(fixture(noise = 3)).state
        )
    }

    @Test fun missingIobFailsClosed() {
        assertEquals(
            BoostV1DetermineBasalPreflight.State.INVALID_INPUT,
            BoostV1DetermineBasalPreflight.inspect(fixture().copy(iob = emptyList())).state
        )
    }
}
