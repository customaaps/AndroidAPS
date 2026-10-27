package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1ApsTypeBoundary
import app.aaps.plugins.aps.openAPSBoost.BoostV1CgmGateShadow
import kotlin.test.Test
import kotlin.test.assertEquals

class BoostV1ApsBoundaryGlucoseTest {
    private fun input(bg: Double, noise: Int) = BoostV1ApsTypeBoundary.Inputs(
        glucose = BoostV1CgmGateShadow.Input(
            currentTimeMs = 1_800_000L, glucoseTimeMs = 1_500_000L,
            bgMgDl = bg, noise = noise, flatBGsDetected = false
        ),
        currentTempRate = 0.5, currentTempMinutes = 20,
        profile = BoostV1ApsTypeBoundary.Profile(
            currentBasal = 0.5, maxBasal = 2.0, maxDailyBasal = 1.0,
            maxDailySafetyMultiplier = 3.0, currentBasalSafetyMultiplier = 4.0,
            skipNeutralTemps = true
        ),
        iob = listOf(BoostV1ApsTypeBoundary.IobPoint(1_500_000L, 0.3, 0.01))
    )

    @Test fun nonFiniteBgFailsBeforeComparison() {
        assertEquals(
            BoostV1ApsTypeBoundary.Validation.INVALID_GLUCOSE,
            BoostV1ApsTypeBoundary.validate(input(Double.NaN, 0))
        )
    }

    @Test fun impossibleNoiseFailsBeforeComparison() {
        assertEquals(
            BoostV1ApsTypeBoundary.Validation.INVALID_GLUCOSE,
            BoostV1ApsTypeBoundary.validate(input(110.0, -1))
        )
    }

    @Test fun validGlucoseMayProceedToOfflineComparison() {
        assertEquals(
            BoostV1ApsTypeBoundary.Validation.VALID,
            BoostV1ApsTypeBoundary.validate(input(110.0, 0))
        )
    }
}
