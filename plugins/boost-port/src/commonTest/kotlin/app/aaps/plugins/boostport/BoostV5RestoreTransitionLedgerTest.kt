package app.aaps.plugins.boostport

import app.aaps.plugins.boostport.telemetry.BoostV5RestoreTransitionLedger
import app.aaps.plugins.boostport.telemetry.BoostV5StateSnapshotCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BoostV5RestoreTransitionLedgerTest {
    private val ledger = BoostV5RestoreTransitionLedger
    private fun valid() = assertNotNull(BoostV5StateSnapshotCodec.encode(BoostV5StateSnapshotCodec.Snapshot()))

    @Test fun canonicalDefaultsDoNotCountAsTransitions() {
        val full = valid()
        val older = full.filterKeys { it == "mealHypothesis" || it == "mealHypothesisAge" }
        val report = ledger.inspect(listOf(BoostV5RestoreTransitionLedger.Frame(100L, older), BoostV5RestoreTransitionLedger.Frame(200L, full)))
        assertEquals(BoostV5RestoreTransitionLedger.Finding.VALID, report.finding)
        assertTrue(report.changes.single().changedKeys.isEmpty())
    }

    @Test fun genuineChangesAreListedWithoutModifyingState() {
        val before = valid()
        val after = before + ("mlMealLikelyNullStreak" to "3")
        val report = ledger.inspect(listOf(BoostV5RestoreTransitionLedger.Frame(100L, before), BoostV5RestoreTransitionLedger.Frame(200L, after)))
        assertEquals(BoostV5RestoreTransitionLedger.Finding.VALID, report.finding)
        assertEquals(setOf("mlMealLikelyNullStreak"), report.changes.single().changedKeys)
    }

    @Test fun badFramesFailClosedWithIndex() {
        val data = valid()
        assertEquals(BoostV5RestoreTransitionLedger.Finding.EMPTY, ledger.inspect(emptyList()).finding)
        assertEquals(BoostV5RestoreTransitionLedger.Finding.INVALID_TIMESTAMP, ledger.inspect(listOf(BoostV5RestoreTransitionLedger.Frame(-1L, data))).finding)
        assertEquals(BoostV5RestoreTransitionLedger.Finding.NON_MONOTONIC_TIME, ledger.inspect(listOf(
            BoostV5RestoreTransitionLedger.Frame(100L, data), BoostV5RestoreTransitionLedger.Frame(100L, data))).finding)
        val invalid = ledger.inspect(listOf(BoostV5RestoreTransitionLedger.Frame(100L, data), BoostV5RestoreTransitionLedger.Frame(200L, data + ("mealHypothesisAge" to "bad"))))
        assertEquals(BoostV5RestoreTransitionLedger.Finding.INVALID_SNAPSHOT, invalid.finding)
        assertEquals(1, invalid.index)
        assertEquals(BoostV5RestoreTransitionLedger.Finding.FUTURE_STATE_CLOCK, ledger.inspect(listOf(
            BoostV5RestoreTransitionLedger.Frame(100L, data + ("lastCommitMs" to "200")))).finding)
    }
}
