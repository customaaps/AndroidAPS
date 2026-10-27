package app.aaps.plugins.boostport

import app.aaps.plugins.boostport.telemetry.BoostV1V5OfflineIntegration
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals

class BoostV1V5OfflineIntegrationBoundaryTest {
    @Test fun invalidGuardReportCannotEnableDosing() {
        val rejected = BoostV1V5OfflineIntegration.Report(
            BoostV1V5OfflineIntegration.Finding.INVALID_V1_GUARD
        )
        assertEquals(0, rejected.reviewedCycles)
        assertFalse(rejected.canDeliverInsulin)
    }
}
