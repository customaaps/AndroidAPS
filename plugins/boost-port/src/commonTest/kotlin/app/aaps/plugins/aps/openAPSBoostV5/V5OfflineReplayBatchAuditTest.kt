package app.aaps.plugins.aps.openAPSBoostV5

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class V5OfflineReplayBatchAuditTest {
    private fun frame(fields: Map<String, String>?): V5OfflineBatchReplay.Frame =
        V5OfflineBatchReplay.Frame(fields, true, true, true)

    @Test fun emptyBatchIsRejected() {
        val report = V5OfflineReplayBatchAudit.inspect(emptyList())
        assertEquals(V5OfflineReplayBatchAudit.Finding.EMPTY, report.finding)
        assertEquals(0, report.acceptedFrames)
        assertFalse(report.liveInvocationAllowed)
    }

    @Test fun malformedStateIsRejectedBeforeChronology() {
        val report = V5OfflineReplayBatchAudit.inspect(listOf(frame(mapOf("bad" to "state"))))
        assertEquals(V5OfflineReplayBatchAudit.Finding.PREREQUISITE_REJECTED, report.finding)
        assertEquals(0, report.rejectedIndex)
        assertFalse(report.insulinDeliveryAllowed)
    }

    @Test fun missingV1RouteIsRejected() {
        val report = V5OfflineReplayBatchAudit.inspect(
            listOf(V5OfflineBatchReplay.Frame(null, false, false, true))
        )
        assertEquals(V5OfflineReplayBatchAudit.Finding.PREREQUISITE_REJECTED, report.finding)
        assertEquals(0, report.acceptedFrames)
        assertFalse(report.liveInvocationAllowed)
    }
}
