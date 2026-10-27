package app.aaps.plugins.boostport

import app.aaps.plugins.boostport.telemetry.BoostV1V5OfflineIntegration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostV1V5OfflineIntegrationTest {
    @Test fun emptyCycleListIsRejectedBeforeAnyReplay() {
        val report = BoostV1V5OfflineIntegration.Report(
            BoostV1V5OfflineIntegration.Finding.INVALID_SESSION
        )
        assertEquals(BoostV1V5OfflineIntegration.Finding.INVALID_SESSION, report.finding)
        assertFalse(report.canDeliverInsulin)
    }
}
