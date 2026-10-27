package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1ExpectedDeltaShadow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoostV1ExpectedDeltaShadowTest {
    @Test fun unchangedTwoHourProjectionAndRounding() {
        val r = BoostV1ExpectedDeltaShadow.inspect(100.0, 124.0, -2.0)
        assertEquals(BoostV1ExpectedDeltaShadow.Finding.READY, r.finding)
        assertEquals(-3.0, r.expectedDelta)
        assertEquals(0.0, BoostV1ExpectedDeltaShadow.inspect(100.0, 100.0, 0.0).expectedDelta)
    }

    @Test fun missingOrExtremeReadingsFailClosed() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            val result = BoostV1ExpectedDeltaShadow.inspect(value, 100.0, 0.0)
            assertEquals(BoostV1ExpectedDeltaShadow.Finding.INVALID_INPUT, result.finding)
            assertNull(result.expectedDelta)
        }
    }
}
