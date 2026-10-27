package app.aaps.plugins.aps.openAPSBoostV5

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class V5OfflineBatchReplayTest {
    @Test fun emptyBatchCannotEnterReplay() {
        val report = V5OfflineBatchReplay.inspect(emptyList())
        assertEquals(V5OfflineBatchReplay.Finding.EMPTY_BATCH, report.finding)
        assertEquals(0, report.acceptedFrames)
        assertFalse(report.liveInvocationAllowed)
        assertFalse(report.insulinDeliveryAllowed)
    }

    @Test fun invalidSnapshotRejectsBatchAtFirstFrame() {
        val report = V5OfflineBatchReplay.inspect(listOf(
            V5OfflineBatchReplay.Frame(mapOf("invalid" to "value"), true, true, true),
            V5OfflineBatchReplay.Frame(null, true, true, true)
        ))
        assertEquals(V5OfflineBatchReplay.Finding.FRAME_REJECTED, report.finding)
        assertEquals(0, report.acceptedFrames)
        assertEquals(0, report.rejectedFrameIndex)
        assertFalse(report.insulinDeliveryAllowed)
    }

    @Test fun missingBaseEngineCannotEnterReplay() {
        val report = V5OfflineBatchReplay.inspect(listOf(
            V5OfflineBatchReplay.Frame(null, false, true, true)
        ))
        assertEquals(V5OfflineBatchReplay.Finding.FRAME_REJECTED, report.finding)
        assertEquals(0, report.rejectedFrameIndex)
        assertFalse(report.liveInvocationAllowed)
    }
}
