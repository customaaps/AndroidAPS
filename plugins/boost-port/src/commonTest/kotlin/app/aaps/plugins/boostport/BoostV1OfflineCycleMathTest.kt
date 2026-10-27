package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1OfflineCycleMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class BoostV1OfflineCycleMathTest {
    @Test fun combinesSourceDerivedDiagnosticsOnly() {
        val result = BoostV1OfflineCycleMath.inspect(
            BoostV1OfflineCycleMath.Input(145.0, 100.0, 124.0, -2.0, 15)
        )
        assertEquals(BoostV1OfflineCycleMath.Finding.READY, result.finding)
        assertEquals(0.65, result.reboundScale!!, 1e-12)
        assertEquals(-3.0, result.expectedDelta)
        assertEquals(1.0, result.circadianFactor)
        assertFalse(result.canDeliverInsulin)
    }

    @Test fun invalidFieldInvalidatesWholeReport() {
        val result = BoostV1OfflineCycleMath.inspect(
            BoostV1OfflineCycleMath.Input(145.0, 100.0, 124.0, -2.0, 25)
        )
        assertEquals(BoostV1OfflineCycleMath.Finding.INVALID_INPUT, result.finding)
        assertNull(result.reboundScale)
        assertNull(result.expectedDelta)
        assertNull(result.circadianFactor)
        assertFalse(result.canDeliverInsulin)
    }
}
