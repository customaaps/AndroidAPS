package app.aaps.plugins.boostport

import app.aaps.plugins.boostport.telemetry.BoostV1LowGlucoseBranchAudit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostV1LowGlucoseBranchAuditTest {
    @Test fun lowNaiveBgOnlyClassifiesHistoricalBranch() {
        val r = BoostV1LowGlucoseBranchAudit.inspect(
            BoostV1LowGlucoseBranchAudit.Input(65.0, 90.0, 35.0, 3.0, 1.0, 0.0)
        )
        assertEquals(BoostV1LowGlucoseBranchAudit.Finding.NAIVE_CRITICAL_LOW, r.finding)
        assertFalse(r.canDeliverInsulin)
    }
    @Test fun invalidValueIsRejected() {
        assertEquals(BoostV1LowGlucoseBranchAudit.Finding.INVALID,
            BoostV1LowGlucoseBranchAudit.inspect(
                BoostV1LowGlucoseBranchAudit.Input(Double.NaN, 90.0, 35.0, 3.0, 1.0, 0.0)
            ).finding)
    }
}
