package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1LowGlucoseThresholdShadow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoostV1LowGlucoseThresholdShadowTest {
    @Test fun baseline() {
        val report = BoostV1LowGlucoseThresholdShadow.inspect(
            BoostV1LowGlucoseThresholdShadow.Input(100.0, null, 0.0))
        assertEquals(70.0, report.thresholdMgDl)
        assertFalse(report.canDeliverInsulin)
    }
    @Test fun configuredFloor() {
        val report = BoostV1LowGlucoseThresholdShadow.inspect(
            BoostV1LowGlucoseThresholdShadow.Input(100.0, 80.0, 0.0))
        assertEquals(80.0, report.thresholdMgDl)
    }
    @Test fun accelerationOverrideMatchesSourceOrder() {
        val report = BoostV1LowGlucoseThresholdShadow.inspect(
            BoostV1LowGlucoseThresholdShadow.Input(100.0, 90.0, 1.0))
        assertEquals(65.0, report.thresholdMgDl)
        assertTrue(report.accelerationOverrideApplied)
    }
    @Test fun nonFiniteRejected() {
        val report = BoostV1LowGlucoseThresholdShadow.inspect(
            BoostV1LowGlucoseThresholdShadow.Input(100.0, null, Double.NaN))
        assertEquals(BoostV1LowGlucoseThresholdShadow.Finding.INVALID_INPUT, report.finding)
    }
}
