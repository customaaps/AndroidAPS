package app.aaps.plugins.boostport

import app.aaps.plugins.boostport.telemetry.BoostV1LowGlucoseBranchAudit
import app.aaps.plugins.boostport.telemetry.BoostV1V5DiagnosticCheckpoint
import app.aaps.plugins.boostport.telemetry.BoostV1V5DiagnosticResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostV1V5DiagnosticCheckpointTest {
    @Test fun criticalLowRequiresReviewButCannotDose() {
        val guard = BoostV1LowGlucoseBranchAudit.Report(
            BoostV1LowGlucoseBranchAudit.Finding.NAIVE_CRITICAL_LOW,
            wouldEnterZeroTempBranch = true
        )
        val session = BoostV1V5DiagnosticResult.Report(
            BoostV1V5DiagnosticResult.Finding.DIAGNOSTIC_ONLY,
            listOf(BoostV1V5DiagnosticResult.Entry(100L, 0.3, "IDLE"))
        )
        val output = BoostV1V5DiagnosticCheckpoint.inspect(
            BoostV1V5DiagnosticCheckpoint.Input(guard, session)
        )
        assertEquals(BoostV1V5DiagnosticCheckpoint.Finding.REQUIRES_REVIEW, output.finding)
        assertFalse(output.canDeliverInsulin)
    }
}
