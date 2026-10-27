package app.aaps.plugins.boostport

import app.aaps.plugins.boostport.telemetry.BoostV1CycleProvenanceAudit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostV1CycleProvenanceAuditTest {
    @Test fun defaultReportCannotEnableInsulin() {
        val report = BoostV1CycleProvenanceAudit.Report(
            BoostV1CycleProvenanceAudit.Finding.INVALID_CLOCK
        )
        assertEquals(BoostV1CycleProvenanceAudit.Finding.INVALID_CLOCK, report.finding)
        assertFalse(report.canDeliverInsulin)
    }
}
