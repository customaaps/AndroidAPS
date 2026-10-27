package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1CarbAbsorptionShadow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostV1CarbAbsorptionShadowTest {
    @Test fun neutralMealReturnsFiniteOfflineDiagnostics() {
        val result = BoostV1CarbAbsorptionShadow.inspect(
            BoostV1CarbAbsorptionShadow.Input(
                nowMs = 1_000_000L, lastCarbMs = 0L, carbs = 0.0, cob = 0.0,
                profileSens = 50.0, effectiveCarbRatio = 10.0,
                sensitivityRatio = 1.0, rawCi = 0.0, remainingCarbsCap = 90,
                slopeFromMaxDeviation = 0.0, slopeFromMinDeviation = 0.0
            )
        )
        assertEquals(BoostV1CarbAbsorptionShadow.Finding.READY, result.finding)
        assertEquals(0.0, result.remainingCiPeak)
        assertFalse(result.canDeliverInsulin)
    }
}
