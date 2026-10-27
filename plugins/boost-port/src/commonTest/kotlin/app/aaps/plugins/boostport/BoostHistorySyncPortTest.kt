package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoostV5.BoostHistorySync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BoostHistorySyncPortTest {
    private val thin = BoostHistorySync.History(daysWithTdd = 2, bgReadings = 400, treatments = 10)
    private val full = BoostHistorySync.History(daysWithTdd = 10, bgReadings = 2200, treatments = 150)
    private val empty = BoostHistorySync.State(attempts = 0, lastAttemptMs = 0L, preBgReadings = 0, preTreatments = 0)
    private val now = 1_800_000_000_000L

    @Test fun sufficientHistoryNeverRequestsBackfill() {
        val d = BoostHistorySync.decide(full, true, now, "now", empty)
        assertFalse(d.requestBackfill)
        assertEquals(0, assertNotNull(d.newState).attempts)
    }

    @Test fun thinHistoryRequestsOnlyWhenNsAvailable() {
        val unavailable = BoostHistorySync.decide(thin, false, now, "now", empty)
        assertFalse(unavailable.requestBackfill)
        assertEquals(0, assertNotNull(unavailable.newState).attempts)
        val available = BoostHistorySync.decide(thin, true, now, "now", empty)
        assertTrue(available.requestBackfill)
        assertEquals(1, assertNotNull(available.newState).attempts)
    }

    @Test fun cooldownBlocksImmediateRetry() {
        val first = BoostHistorySync.decide(thin, true, now, "now", empty)
        val d = BoostHistorySync.decide(thin, true, now + 60_000L, "later", assertNotNull(first.newState))
        assertFalse(d.requestBackfill)
    }

    @Test fun attemptCapBlocksFurtherRequests() {
        val state = empty.copy(attempts = BoostHistorySync.MAX_ATTEMPTS, firstSeenMs = now, lastAttemptMs = now - BoostHistorySync.RETRY_COOLDOWN_MS)
        val d = BoostHistorySync.decide(thin, true, now, "now", state)
        assertFalse(d.requestBackfill)
    }

    @Test fun expiredInstallWindowCannotBackfill() {
        val old = empty.copy(firstSeenMs = now - BoostHistorySync.NEW_INSTALL_WINDOW_MS - 1L)
        val d = BoostHistorySync.decide(thin, true, now, "now", old)
        assertFalse(d.requestBackfill)
    }

    @Test fun filledHistoryResetsAttemptsButKeepsFirstSeenAnchor() {
        val first = BoostHistorySync.decide(thin, true, now, "now", empty)
        val d = BoostHistorySync.decide(full, true, now + 1000L, "later", assertNotNull(first.newState))
        assertFalse(d.requestBackfill)
        assertEquals(0, assertNotNull(d.newState).attempts)
        assertEquals(now, assertNotNull(d.newState).firstSeenMs)
        assertTrue(assertNotNull(d.summary).startsWith("filled:"))
    }

    @Test fun clockRollbackCannotReopenInstallBackfillWindow() {
        val anchored = empty.copy(firstSeenMs = now + 60_000L)
        val result = BoostHistorySync.decide(thin, true, now, "clock-rolled-back", anchored)
        assertFalse(result.requestBackfill)
        assertEquals(0, assertNotNull(result.newState).attempts)
    }

    @Test fun clockRollbackDuringCooldownCannotRepeatBackfill() {
        val state = empty.copy(attempts = 1, firstSeenMs = now - 60_000L, lastAttemptMs = now + 60_000L)
        val result = BoostHistorySync.decide(thin, true, now, "clock-rolled-back", state)
        assertFalse(result.requestBackfill)
    }

    @Test fun invalidNegativeHistoryCannotStartNightscoutBackfill() {
        val invalid = listOf(
            thin.copy(daysWithTdd = -1),
            thin.copy(bgReadings = -1),
            thin.copy(treatments = -1)
        )
        for (snapshot in invalid) {
            val decision = BoostHistorySync.decide(snapshot, true, now, "now", empty)
            assertFalse(decision.requestBackfill)
            assertEquals("skipped:invalid-history@now", decision.summary)
            assertEquals(0, assertNotNull(decision.newState).attempts)
        }
    }

    @Test fun futureBackfillAttemptTimestampCannotTriggerRetry() {
        val future = empty.copy(
            attempts = 1,
            firstSeenMs = now - 60_000L,
            lastAttemptMs = Long.MAX_VALUE
        )
        val result = BoostHistorySync.decide(thin, true, now, "now", future)
        assertFalse(result.requestBackfill)
        assertEquals(null, result.summary)
    }

    @Test fun negativePersistedAttemptCounterCannotTriggerBackfill() {
        val damagedState = empty.copy(attempts = -1, firstSeenMs = now)
        val decision = BoostHistorySync.decide(thin, true, now, "now", damagedState)
        assertFalse(decision.requestBackfill)
        assertEquals("skipped:invalid-backfill-state@now", decision.summary)
        assertEquals(-1, assertNotNull(decision.newState ?: damagedState).attempts)
    }

    @Test fun recoveredCountsReportLargeValidCountsWithoutOverflow() {
        // Negative persisted counters are rejected before reporting; use the largest valid Int counts.
        val before = empty.copy(attempts = 1, firstSeenMs = now, preBgReadings = 0, preTreatments = 0)
        val recovered = full.copy(bgReadings = Int.MAX_VALUE, treatments = Int.MAX_VALUE)
        val result = BoostHistorySync.decide(recovered, true, now, "now", before)
        assertFalse(result.requestBackfill)
        assertTrue(assertNotNull(result.summary).contains("bg=+2147483647"))
        assertTrue(assertNotNull(result.summary).contains("treatments=+2147483647"))
    }

    @Test fun sufficientHistoryDoesNotMaskDamagedState() {
        val corrupt = empty.copy(attempts = -1, firstSeenMs = now)
        val result = BoostHistorySync.decide(full, true, now, "now", corrupt)
        assertFalse(result.requestBackfill)
        assertEquals("skipped:invalid-backfill-state@now", result.summary)
    }

    @Test fun sufficientHistoryDoesNotMaskInvalidCounts() {
        val corrupt = full.copy(treatments = -1)
        val result = BoostHistorySync.decide(corrupt, true, now, "now", empty)
        assertFalse(result.requestBackfill)
        assertEquals("skipped:invalid-history@now", result.summary)
    }

    @Test fun corruptedPreImportSnapshotCannotReportRecoveryOrRequestBackfill() {
        for (state in listOf(
            empty.copy(attempts = 1, firstSeenMs = now, preBgReadings = -1),
            empty.copy(attempts = 1, firstSeenMs = now, preTreatments = -1)
        )) {
            val fullDecision = BoostHistorySync.decide(full, true, now, "now", state)
            assertFalse(fullDecision.requestBackfill)
            assertEquals("skipped:invalid-backfill-state@now", fullDecision.summary)
            val thinDecision = BoostHistorySync.decide(thin, true, now, "now", state)
            assertFalse(thinDecision.requestBackfill)
        }
    }

    @Test fun negativeEpochOrInstallAnchorCannotInitiateImport() {
        val invalidClock = BoostHistorySync.decide(thin, true, -1L, "bad-clock", empty)
        assertFalse(invalidClock.requestBackfill)
        assertEquals("skipped:invalid-backfill-time@bad-clock", invalidClock.summary)

        val invalidAnchor = BoostHistorySync.decide(
            thin, true, now, "bad-anchor", empty.copy(firstSeenMs = -1L)
        )
        assertFalse(invalidAnchor.requestBackfill)
        assertEquals("skipped:invalid-backfill-time@bad-anchor", invalidAnchor.summary)
    }
}
