package app.aaps.plugins.boostport

import app.aaps.plugins.boostport.telemetry.BoostV1V5OfflineReplaySequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostV1V5OfflineReplaySequenceTest {
    @Test fun emptyHistoryCannotBeReportedReady() {
        val report = BoostV1V5OfflineReplaySequence.inspect(emptyList())
        assertEquals(BoostV1V5OfflineReplaySequence.Finding.EMPTY, report.finding)
        assertFalse(report.canDeliverInsulin)
    }
}
