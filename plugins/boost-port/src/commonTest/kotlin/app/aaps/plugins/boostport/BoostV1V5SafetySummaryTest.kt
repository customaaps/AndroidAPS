package app.aaps.plugins.boostport

import app.aaps.plugins.boostport.telemetry.BoostV1CycleProvenanceAudit
import app.aaps.plugins.boostport.telemetry.BoostV1V5DiagnosticCheckpoint
import app.aaps.plugins.boostport.telemetry.BoostV1V5SafetySummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostV1V5SafetySummaryTest {
    @Test fun criticalLowRemainsDiagnosticOnly() {
        val checkpoint = BoostV1V5DiagnosticCheckpoint.Report(
            BoostV1V5DiagnosticCheckpoint.Finding.REQUIRES_REVIEW,
            lowGlucoseFlag = true, observationCount = 2
        )
        val cycle = BoostV1CycleProvenanceAudit.Report(BoostV1CycleProvenanceAudit.Finding.READY)
        val report = BoostV1V5SafetySummary.inspect(BoostV1V5SafetySummary.Input(checkpoint, cycle))
        assertEquals(BoostV1V5SafetySummary.Finding.CRITICAL_LOW_REVIEW, report.finding)
        assertFalse(report.canDeliverInsulin)
    }
}
