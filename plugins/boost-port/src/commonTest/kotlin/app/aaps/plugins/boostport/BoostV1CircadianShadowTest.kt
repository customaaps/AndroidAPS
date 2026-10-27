package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1CircadianShadow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoostV1CircadianShadowTest {
    @Test fun archivedCurveMiddayPlateauAndEarlyNight() {
        assertEquals(1.0, BoostV1CircadianShadow.inspect(15).factor)
        assertEquals(1.0, BoostV1CircadianShadow.inspect(22).factor)
        assertEquals(1.32826, BoostV1CircadianShadow.inspect(0).factor!!, 1e-10)
        val result = BoostV1CircadianShadow.inspect(23)
        assertEquals(BoostV1CircadianShadow.Finding.READY, result.finding)
        assertEquals(0.000125 * 23.0 * 23.0 * 23.0 - 0.0015 * 23.0 * 23.0 - 0.0045 * 23.0 + 1.2,
            result.factor!!, 1e-10)
    }

    @Test fun invalidClockNeverProducesFactor() {
        for (hour in listOf(-1, 24, 100)) {
            val result = BoostV1CircadianShadow.inspect(hour)
            assertEquals(BoostV1CircadianShadow.Finding.INVALID_HOUR, result.finding)
            assertNull(result.factor)
        }
    }
}
