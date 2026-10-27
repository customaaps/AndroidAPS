package app.aaps.plugins.boostport

import app.aaps.plugins.boostport.telemetry.BoostPortPassiveApsResultAdapter
import app.aaps.plugins.boostport.telemetry.BoostV1V5OfflineIntegration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostPortPassiveApsResultAdapterTest {
    @Test fun invalidSourceDoesNotInstantiateAnApsResult() {
        val r = BoostPortPassiveApsResultAdapter.adapt(
            BoostV1V5OfflineIntegration.Report(
                BoostV1V5OfflineIntegration.Finding.INVALID_V1_GUARD
            ),
            100L,
            newResult = { error("must not instantiate APSResult for invalid replay") }
        )
        assertEquals(BoostPortPassiveApsResultAdapter.Finding.INVALID_REPORT, r.finding)
        assertFalse(r.canDeliverInsulin)
    }
}
