package app.aaps.plugins.boostport

import app.aaps.plugins.boostport.telemetry.BoostV5OfflineRestoreWindowAudit
import app.aaps.plugins.boostport.telemetry.BoostV5RestoreTransitionLedger
import app.aaps.plugins.boostport.telemetry.BoostV5StateSnapshotCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BoostV5OfflineRestoreWindowAuditTest {
    private val audit = BoostV5OfflineRestoreWindowAudit
    private val ledger = BoostV5RestoreTransitionLedger
    private fun fields() = assertNotNull(BoostV5StateSnapshotCodec.encode(BoostV5StateSnapshotCodec.Snapshot()))

    @Test fun emptyWindowAndSingleValidFrame() {
        assertEquals(BoostV5OfflineRestoreWindowAudit.Finding.EMPTY, audit.inspect(emptyList()).finding)
        val single = audit.inspect(listOf(BoostV5RestoreTransitionLedger.Frame(1L, fields())))
        assertEquals(BoostV5OfflineRestoreWindowAudit.Finding.VALID, single.finding)
        assertEquals(0, single.changedFrameCount)
    }

    @Test fun oversizedWindowFailsBeforeDecodingFrames() {
        val oversized = List(audit.MAX_FRAMES + 1) { BoostV5RestoreTransitionLedger.Frame(-1L, emptyMap()) }
        assertEquals(BoostV5OfflineRestoreWindowAudit.Finding.TOO_MANY_FRAMES, audit.inspect(oversized).finding)
    }

    @Test fun limitIsInclusiveAndCountsOnlyActualChanges() {
        val initial = fields()
        val frames = List(audit.MAX_FRAMES) { index ->
            BoostV5RestoreTransitionLedger.Frame(index.toLong() + 1L, if (index == 1) initial + ("mlMealLikelyNullStreak" to "1") else initial)
        }
        val result = audit.inspect(frames)
        assertEquals(BoostV5OfflineRestoreWindowAudit.Finding.VALID, result.finding)
        assertEquals(2, result.changedFrameCount)
    }

    @Test fun invalidPersistedFrameIsRejected() {
        val report = audit.inspect(listOf(
            BoostV5RestoreTransitionLedger.Frame(1L, fields()),
            BoostV5RestoreTransitionLedger.Frame(2L, mapOf("mealHypothesis" to "CORRUPT")),
        ))
        assertEquals(BoostV5OfflineRestoreWindowAudit.Finding.INVALID_WINDOW, report.finding)
        assertEquals(1, report.firstInvalidIndex)
    }
}
