package app.aaps.plugins.boostport

import app.aaps.plugins.boostport.telemetry.BoostV5MealStateDiagnostics
import app.aaps.plugins.boostport.telemetry.BoostV5OfflineRestoreBatch
import app.aaps.plugins.boostport.telemetry.BoostV5RestoreContinuityAudit
import app.aaps.plugins.boostport.telemetry.BoostV5StateSnapshotCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BoostV5RestoreContinuityAuditTest {
    private val audit = BoostV5RestoreContinuityAudit
    private val batch = BoostV5OfflineRestoreBatch

    private fun snapshot(locked: Boolean, commitMs: Long = 0L): Map<String, String> =
        assertNotNull(BoostV5StateSnapshotCodec.encode(
            BoostV5StateSnapshotCodec.Snapshot(
                meal = BoostV5MealStateDiagnostics.State(
                    committedInSession = locked, lastCommitMs = commitMs
                ),
            ),
        ))

    @Test fun emptyAndMissingFramesAreNotReportedAsConsistent() {
        assertEquals(BoostV5RestoreContinuityAudit.Finding.EMPTY, audit.inspect(emptyList()).finding)
        val report = audit.inspect(listOf(BoostV5OfflineRestoreBatch.Frame(100L, snapshot(false)), BoostV5OfflineRestoreBatch.Frame(200L, null)))
        assertEquals(BoostV5RestoreContinuityAudit.Finding.MISSING_SNAPSHOT, report.finding)
        assertEquals(listOf(1), report.problematicIndices)
    }

    @Test fun duplicateTimeIsExplicitlyReported() {
        val report = audit.inspect(listOf(BoostV5OfflineRestoreBatch.Frame(100L, snapshot(false)), BoostV5OfflineRestoreBatch.Frame(100L, snapshot(false))))
        assertEquals(BoostV5RestoreContinuityAudit.Finding.NON_CHRONOLOGICAL, report.finding)
    }

    @Test fun corruptPersistedDataCannotPassContinuity() {
        val report = audit.inspect(listOf(BoostV5OfflineRestoreBatch.Frame(100L, mapOf("mealHypothesis" to "BOGUS"))))
        assertEquals(BoostV5RestoreContinuityAudit.Finding.INVALID_SNAPSHOT, report.finding)
    }

    @Test fun prematureLockDisappearanceIsFlaggedForReview() {
        val report = audit.inspect(listOf(
            BoostV5OfflineRestoreBatch.Frame(100_000L, snapshot(true, 90_000L)),
            BoostV5OfflineRestoreBatch.Frame(200_000L, snapshot(false)),
        ))
        assertEquals(BoostV5RestoreContinuityAudit.Finding.SESSION_LOCK_REGRESSION, report.finding)
        assertEquals(listOf(1), report.problematicIndices)
    }

    @Test fun retainedLockIsConsistent() {
        val report = audit.inspect(listOf(
            BoostV5OfflineRestoreBatch.Frame(100_000L, snapshot(true, 90_000L)),
            BoostV5OfflineRestoreBatch.Frame(200_000L, snapshot(true, 90_000L)),
        ))
        assertEquals(BoostV5RestoreContinuityAudit.Finding.CONSISTENT, report.finding)
    }
}
