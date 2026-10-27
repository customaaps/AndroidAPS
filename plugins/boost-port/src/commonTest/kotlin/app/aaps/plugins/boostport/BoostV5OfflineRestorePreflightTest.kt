package app.aaps.plugins.boostport

import app.aaps.plugins.boostport.telemetry.BoostV5OfflineRestorePreflight
import app.aaps.plugins.boostport.telemetry.BoostV5StateSnapshotCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class BoostV5OfflineRestorePreflightTest {
    private val gate = BoostV5OfflineRestorePreflight

    @Test fun blankColdStartIsDistinctFromCorruption() {
        assertEquals(BoostV5OfflineRestorePreflight.Finding.ABSENT, gate.inspect(null, 100L).finding)
        assertEquals(BoostV5OfflineRestorePreflight.Finding.ABSENT, gate.inspect(emptyMap(), 100L).finding)
        assertEquals(BoostV5OfflineRestorePreflight.Finding.CORRUPT, gate.inspect(mapOf("mealHypothesis" to "IDLE"), 100L).finding)
    }

    @Test fun restoredDefaultStateCanBeAudited() {
        val fields = assertNotNull(BoostV5StateSnapshotCodec.encode(BoostV5StateSnapshotCodec.Snapshot()))
        assertEquals(BoostV5OfflineRestorePreflight.Finding.READY, gate.inspect(fields, 100L).finding)
        assertEquals(BoostV5OfflineRestorePreflight.Finding.INVALID_REPLAY_TIME, gate.inspect(fields, -1L).finding)
    }

    @Test fun futureClockReportsSpecificFieldsWithoutClearingThem() {
        val fields = assertNotNull(BoostV5StateSnapshotCodec.encode(BoostV5StateSnapshotCodec.Snapshot()))
        val report = gate.inspect(fields + ("lastCommitMs" to "300"), 100L)
        assertEquals(BoostV5OfflineRestorePreflight.Finding.FUTURE_CLOCK, report.finding)
        assertTrue("lastCommitMs" in report.futureFields)
    }

    @Test fun nonfinitePersistedStateFailsClosed() {
        val fields = assertNotNull(BoostV5StateSnapshotCodec.encode(BoostV5StateSnapshotCodec.Snapshot()))
        assertEquals(BoostV5OfflineRestorePreflight.Finding.CORRUPT, gate.inspect(fields + ("primerIobU" to "NaN"), 100L).finding)
    }
}
