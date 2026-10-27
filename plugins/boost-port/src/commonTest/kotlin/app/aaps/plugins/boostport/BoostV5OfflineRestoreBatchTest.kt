package app.aaps.plugins.boostport

import app.aaps.plugins.boostport.telemetry.BoostV5OfflineRestoreBatch
import app.aaps.plugins.boostport.telemetry.BoostV5OfflineRestorePreflight
import app.aaps.plugins.boostport.telemetry.BoostV5StateSnapshotCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class BoostV5OfflineRestoreBatchTest {
    private val batch = BoostV5OfflineRestoreBatch
    private val gate = BoostV5OfflineRestorePreflight

    @Test fun countsEveryFrameWithoutSkippingCorruption() {
        val valid = assertNotNull(BoostV5StateSnapshotCodec.encode(BoostV5StateSnapshotCodec.Snapshot()))
        val summary = batch.inspect(listOf(
            BoostV5OfflineRestoreBatch.Frame(100L, null),
            BoostV5OfflineRestoreBatch.Frame(200L, valid),
            BoostV5OfflineRestoreBatch.Frame(300L, valid + ("primerIobU" to "NaN")),
            BoostV5OfflineRestoreBatch.Frame(400L, valid + ("lastCommitMs" to "800")),
        ))
        assertEquals(4, summary.entries.size)
        assertEquals(1, summary.ready)
        assertEquals(1, summary.absent)
        assertEquals(2, summary.rejected)
        assertTrue(summary.chronological)
        assertEquals(BoostV5OfflineRestorePreflight.Finding.CORRUPT, summary.entries[2].finding)
        assertEquals(BoostV5OfflineRestorePreflight.Finding.FUTURE_CLOCK, summary.entries[3].finding)
        assertEquals(setOf("lastCommitMs"), summary.entries[3].futureFields)
    }

    @Test fun duplicateAndBackwardsFramesRemainVisible() {
        val summary = batch.inspect(listOf(
            BoostV5OfflineRestoreBatch.Frame(100L, null), BoostV5OfflineRestoreBatch.Frame(100L, null), BoostV5OfflineRestoreBatch.Frame(90L, null)
        ))
        assertFalse(summary.chronological)
        assertEquals(listOf(0, 1, 2), summary.entries.map { it.index })
    }

    @Test fun emptyBatchIsChronologicalAndHasZeroCounts() {
        val summary = batch.inspect(emptyList())
        assertTrue(summary.chronological)
        assertEquals(0, summary.ready)
        assertEquals(0, summary.absent)
        assertEquals(0, summary.rejected)
    }

    @Test fun invalidFrameTimeRemainsRejection() {
        val summary = batch.inspect(listOf(BoostV5OfflineRestoreBatch.Frame(-1L, null)))
        assertEquals(BoostV5OfflineRestorePreflight.Finding.INVALID_REPLAY_TIME, summary.entries.single().finding)
        assertEquals(1, summary.rejected)
    }
}
