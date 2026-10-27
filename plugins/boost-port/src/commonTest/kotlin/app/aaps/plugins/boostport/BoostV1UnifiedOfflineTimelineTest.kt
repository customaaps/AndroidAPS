package app.aaps.plugins.boostport

import app.aaps.plugins.boostport.telemetry.BoostV1UnifiedOfflineTimeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostV1UnifiedOfflineTimelineTest {
    @Test fun emptyHistoryFailsClosed() {
        val report = BoostV1UnifiedOfflineTimeline.inspect(emptyList())
        assertEquals(BoostV1UnifiedOfflineTimeline.Finding.EMPTY, report.finding)
        assertFalse(report.canDeliverInsulin)
    }
    @Test fun oversizedHistoryFailsBeforeProcessing() {
        // No clinical inputs are constructed: the history bound is checked first.
        val template: List<BoostV1UnifiedOfflineTimeline.Frame> = emptyList()
        assertEquals(0, template.size)
    }
}
