package app.aaps.plugins.boostport

import app.aaps.plugins.boostport.telemetry.BoostV5MealStateDiagnostics
import app.aaps.plugins.boostport.telemetry.BoostV5RestoreEvidenceAudit
import app.aaps.plugins.boostport.telemetry.BoostV5StateSnapshotCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BoostV5RestoreEvidenceAuditTest {
    private val audit = BoostV5RestoreEvidenceAudit
    private fun stored(lock: Boolean, commit: Long): Map<String, String> =
        assertNotNull(BoostV5StateSnapshotCodec.encode(
            BoostV5StateSnapshotCodec.Snapshot(
                meal = BoostV5MealStateDiagnostics.State(
                    committedInSession = lock, lastCommitMs = commit
                )
            )
        ))

    @Test fun insufficientEvidenceDoesNotProveRegression() {
        val report = audit.inspect(listOf(
            BoostV5RestoreEvidenceAudit.Frame(100_000L, stored(true, 90_000L)),
            BoostV5RestoreEvidenceAudit.Frame(200_000L, stored(false, 90_000L))
        ))
        assertEquals(BoostV5RestoreEvidenceAudit.Finding.EARLY_DROP_REQUIRES_REVIEW, report.finding)
    }

    @Test fun shortNonPositiveRunCannotExplainUnlock() {
        val report = audit.inspect(listOf(
            BoostV5RestoreEvidenceAudit.Frame(100_000L, stored(true, 90_000L)),
            BoostV5RestoreEvidenceAudit.Frame(200_000L, stored(false, 90_000L), 150_000L)
        ))
        assertEquals(BoostV5RestoreEvidenceAudit.Finding.EARLY_DROP_WITHOUT_EVIDENCE, report.finding)
    }

    @Test fun supportedNonPositiveReleaseIsNotFlagged() {
        val report = audit.inspect(listOf(
            BoostV5RestoreEvidenceAudit.Frame(2_000_000L, stored(true, 100_000L)),
            BoostV5RestoreEvidenceAudit.Frame(4_100_000L, stored(false, 100_000L), 2_100_000L)
        ))
        assertEquals(BoostV5RestoreEvidenceAudit.Finding.LOCK_EXPIRED, report.finding)
    }

    @Test fun chronologyAndInvalidStateAreRejected() {
        assertEquals(BoostV5RestoreEvidenceAudit.Finding.NO_FRAMES, audit.inspect(emptyList()).finding)
        assertEquals(BoostV5RestoreEvidenceAudit.Finding.INVALID_SEQUENCE, audit.inspect(listOf(
            BoostV5RestoreEvidenceAudit.Frame(200L, stored(false, 0L)),
            BoostV5RestoreEvidenceAudit.Frame(100L, stored(false, 0L))
        )).finding)
    }
}
