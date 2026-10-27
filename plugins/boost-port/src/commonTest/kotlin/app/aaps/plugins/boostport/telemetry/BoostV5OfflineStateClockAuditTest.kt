package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoostV5OfflineStateClockAuditTest {
    private val audit = BoostV5OfflineStateClockAudit

    @Test fun emptyClocksAreValidUnsetMarkers() {
        assertEquals(BoostV5OfflineStateClockAudit.Finding.CONSISTENT,
            audit.inspect(1_000L, BoostV5StateSnapshotCodec.Snapshot()).finding)
    }

    @Test fun futurePrimerClockIsReportedWithoutModifyingState() {
        val original = BoostV5StateSnapshotCodec.Snapshot(primerIobUpdatedMs = 2_000L)
        val result = audit.inspect(1_000L, original)
        assertEquals(BoostV5OfflineStateClockAudit.Finding.FUTURE_STATE_CLOCK, result.finding)
        assertTrue("primerIobUpdatedMs" in result.futureFields)
        assertEquals(2_000L, original.primerIobUpdatedMs)
    }

    @Test fun invalidFrameTimestampIsRejected() {
        assertEquals(BoostV5OfflineStateClockAudit.Finding.INVALID_FRAME_TIME,
            audit.inspect(-1L, BoostV5StateSnapshotCodec.Snapshot()).finding)
    }

    @Test fun invalidStateIsRejected() {
        val invalid = BoostV5StateSnapshotCodec.Snapshot(primerIobU = Double.NaN)
        assertEquals(BoostV5OfflineStateClockAudit.Finding.INVALID_STATE,
            audit.inspect(1_000L, invalid).finding)
    }
}
