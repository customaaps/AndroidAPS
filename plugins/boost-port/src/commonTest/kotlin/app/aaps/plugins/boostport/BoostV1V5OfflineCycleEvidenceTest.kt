package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1OfflineCycleMath
import app.aaps.plugins.boostport.telemetry.BoostV1V5OfflineCycleEvidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostV1V5OfflineCycleEvidenceTest {
    private fun frame(at: Long) = BoostV1V5OfflineCycleEvidence.Frame(
        at, BoostV1OfflineCycleMath.Input(145.0, 100.0, 124.0, -2.0, 15),
        0.5, "OBSERVING"
    )

    @Test fun validCallerFedEvidenceProducesNoDosingPermission() {
        val report = BoostV1V5OfflineCycleEvidence.inspect(listOf(frame(1), frame(2)))
        assertEquals(BoostV1V5OfflineCycleEvidence.Finding.READY, report.finding)
        assertEquals(2, report.entries.size)
        assertFalse(report.entries.first().v1.canDeliverInsulin)
    }

    @Test fun missingEvidenceAndDuplicateTimesFailClosed() {
        val missing = frame(1).copy(v5Score = null)
        assertEquals(BoostV1V5OfflineCycleEvidence.Finding.MISSING_V5_EVIDENCE,
            BoostV1V5OfflineCycleEvidence.inspect(listOf(missing)).finding)
        assertEquals(BoostV1V5OfflineCycleEvidence.Finding.NON_MONOTONIC,
            BoostV1V5OfflineCycleEvidence.inspect(listOf(frame(1), frame(1))).finding)
    }
}
