package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1PostRescueShadow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoostV1PostRescueShadowTest {
    @Test fun valuesMatchArchivedV1PiecewiseFunction() {
        val cases = listOf(
            0.0 to 0.3,
            119.0 to 0.3,
            120.0 to 0.3,
            145.0 to 0.65,
            170.0 to 1.0,
            240.0 to 1.0,
        )
        for ((bg, scale) in cases) {
            val result = BoostV1PostRescueShadow.inspect(bg)
            assertEquals(BoostV1PostRescueShadow.Finding.READY, result.finding)
            assertEquals(scale, result.reboundScale!!, 1e-12)
        }
    }

    @Test fun invalidGlucoseNeverProducesFactor() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            val result = BoostV1PostRescueShadow.inspect(bad)
            assertEquals(BoostV1PostRescueShadow.Finding.INVALID_BG, result.finding)
            assertNull(result.reboundScale)
        }
    }
}
