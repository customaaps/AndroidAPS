package app.aaps.plugins.boostport

import app.aaps.plugins.boostport.telemetry.BoostV5OfflineRestoreConsistencyGate
import app.aaps.plugins.boostport.telemetry.BoostV5RestoreTransitionLedger
import app.aaps.plugins.boostport.telemetry.BoostV5StateSnapshotCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BoostV5OfflineRestoreConsistencyGateTest {
    private val gate = BoostV5OfflineRestoreConsistencyGate
    private val ledger = BoostV5RestoreTransitionLedger
    private fun state() = assertNotNull(
        BoostV5StateSnapshotCodec.encode(BoostV5StateSnapshotCodec.Snapshot())
    )

    @Test fun emptyAndValidHistoriesAreDistinct() {
        assertEquals(BoostV5OfflineRestoreConsistencyGate.Finding.EMPTY, gate.inspect(emptyList()).finding)
        val valid = gate.inspect(listOf(BoostV5RestoreTransitionLedger.Frame(10L, state())))
        assertEquals(BoostV5OfflineRestoreConsistencyGate.Finding.CONSISTENT, valid.finding)
        assertEquals(0, valid.changedFrames)
    }

    @Test fun canonicalChangesCountedAcrossInspections() {
        val original = state()
        val changed = original + ("mlMealLikelyNullStreak" to "1")
        val result = gate.inspect(listOf(
            BoostV5RestoreTransitionLedger.Frame(10L, original), BoostV5RestoreTransitionLedger.Frame(20L, changed),
            BoostV5RestoreTransitionLedger.Frame(30L, changed)
        ))
        assertEquals(BoostV5OfflineRestoreConsistencyGate.Finding.CONSISTENT, result.finding)
        assertEquals(1, result.changedFrames)
    }

    @Test fun corruptAndFutureFramesAreRejected() {
        val valid = state()
        val corrupt = gate.inspect(listOf(
            BoostV5RestoreTransitionLedger.Frame(10L, valid), BoostV5RestoreTransitionLedger.Frame(20L, valid + ("mealHypothesisAge" to "invalid"))
        ))
        assertEquals(BoostV5OfflineRestoreConsistencyGate.Finding.INVALID, corrupt.finding)
        assertEquals(1, corrupt.firstInvalidIndex)
        assertEquals(BoostV5OfflineRestoreConsistencyGate.Finding.INVALID, gate.inspect(listOf(
            BoostV5RestoreTransitionLedger.Frame(10L, valid + ("lastCommitMs" to "11"))
        )).finding)
    }

    @Test fun oversizedHistoryCannotBypassWindowLimit() {
        val valid = state()
        val frames = List(BoostV5OfflineRestoreConsistencyGateLimit) {
            BoostV5RestoreTransitionLedger.Frame(it.toLong() + 1L, valid)
        }
        assertEquals(BoostV5OfflineRestoreConsistencyGate.Finding.INVALID, gate.inspect(frames).finding)
    }

    private companion object {
        const val BoostV5OfflineRestoreConsistencyGateLimit = 1001
    }
}
