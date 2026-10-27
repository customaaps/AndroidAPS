package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1DeltaAccelerationShadow
import app.aaps.plugins.aps.openAPSBoost.BoostV1EventualBgShadow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class BoostV1PredictionShadowTest {
    @Test fun neutralHistoryKeepsExpectedBg() {
        val r = BoostV1EventualBgShadow.inspect(
            BoostV1EventualBgShadow.Input(110.0, 0.0, 0.0, 50.0, 50.0, 0.0, 0.0, 0.0)
        )
        assertEquals(BoostV1EventualBgShadow.Finding.READY, r.finding)
        assertEquals(110.0, r.eventualBg)
        assertFalse(r.canDeliverInsulin)
    }
    @Test fun nonfiniteHistoryRejected() {
        val r = BoostV1EventualBgShadow.inspect(
            BoostV1EventualBgShadow.Input(110.0, 0.0, Double.NaN, 50.0, 50.0, 0.0, 0.0, 0.0)
        )
        assertEquals(BoostV1EventualBgShadow.Finding.INVALID_INPUT, r.finding)
        assertNull(r.eventualBg)
    }
    @Test fun deltaFloorStopsUnboundedAcceleration() {
        val r = BoostV1DeltaAccelerationShadow.inspect(0.0, -0.3)
        assertEquals(BoostV1DeltaAccelerationShadow.Finding.READY, r.finding)
        assertEquals(15.0, r.percent)
    }
}
