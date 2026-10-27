package app.aaps.plugins.boostport

import app.aaps.plugins.boostport.telemetry.BoostPortReadOnlyDisplay
import app.aaps.plugins.boostport.telemetry.BoostV1V5OfflineIntegration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostPortReadOnlyDisplayTest {
    @Test fun absentDataCannotSuggestTreatment() {
        val output = BoostPortReadOnlyDisplay.project(null)
        assertEquals(BoostPortReadOnlyDisplay.Status.NO_DATA, output.status)
        assertFalse(output.canDeliverInsulin)
        assertFalse(output.isTreatmentRecommendation)
    }
    @Test fun invalidIntegrationIsNotDisplayedAsReady() {
        val output = BoostPortReadOnlyDisplay.project(
            BoostV1V5OfflineIntegration.Report(
                BoostV1V5OfflineIntegration.Finding.INVALID_V1_GUARD
            )
        )
        assertEquals(BoostPortReadOnlyDisplay.Status.INVALID_DIAGNOSTICS, output.status)
        assertFalse(output.canDeliverInsulin)
    }
    @Test fun criticalDiagnosticFlagRemainsReadOnly() {
        val output = BoostPortReadOnlyDisplay.project(
            BoostV1V5OfflineIntegration.Report(
                BoostV1V5OfflineIntegration.Finding.READY,
                reviewedCycles = 4,
                riskReviewRequired = true
            )
        )
        assertEquals(BoostPortReadOnlyDisplay.Status.REVIEW_REQUIRED, output.status)
        assertEquals(4, output.cycleCount)
        assertFalse(output.canDeliverInsulin)
        assertFalse(output.isTreatmentRecommendation)
    }
}
