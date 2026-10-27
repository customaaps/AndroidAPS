package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1BasalLimitShadow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class BoostV1BasalLimitShadowTest {
    @Test fun usesSmallestOfThreeLegacyBounds() {
        val result = BoostV1BasalLimitShadow.inspect(
            BoostV1BasalLimitShadow.Input(4.0, 3.0, 1.0, 2.0, 1.0)
        )
        assertEquals(BoostV1BasalLimitShadow.Finding.READY, result.finding)
        assertEquals(2.0, result.diagnosticMaxBasal)
        assertFalse(result.canDeliverInsulin)
    }

    @Test fun badDataFailsClosed() {
        val result = BoostV1BasalLimitShadow.inspect(
            BoostV1BasalLimitShadow.Input(Double.NaN, 3.0, 1.0, 2.0, 1.0)
        )
        assertEquals(BoostV1BasalLimitShadow.Finding.INVALID_INPUT, result.finding)
        assertNull(result.diagnosticMaxBasal)
    }
}
