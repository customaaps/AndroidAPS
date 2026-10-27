package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Pure historical state inspection; no AAPS Preferences or dosing. */
class BoostV5StateContinuityBoundaryTest {
    private val audit = BoostV5StateContinuityDiagnostics
    private val codec = BoostV5StateSnapshotCodec
    private val now = 1_700_000_000_000L

    @Test fun stalePersistedEvidenceIsReportedWithoutErasingCommitLock() {
        val state = BoostV5StateSnapshotCodec.Snapshot(
            meal = BoostV5MealStateDiagnostics.State(
                phase = BoostV5MealStateDiagnostics.Phase.COMMITTED,
                committedInSession = true,
                lastAgeMs = now - 31 * 60_000L,
                lastCommitMs = now - 20 * 60_000L,
            ),
        )
        val restored = audit.inspect(audit.restart(), codec.encode(state), now)
        assertNotNull(restored)
        assertEquals(BoostV5StateContinuityDiagnostics.Source.PERSISTED, restored.source)
        assertTrue(restored.needsResetForStaleEvidence)
        assertTrue(restored.snapshot.meal.committedInSession)
        assertEquals(state, restored.snapshot)
    }

    @Test fun freshEvidenceDoesNotNeedReset() {
        val snapshot = BoostV5StateSnapshotCodec.Snapshot(
            meal = BoostV5MealStateDiagnostics.State(lastAgeMs = now - 4 * 60_000L)
        )
        val result = audit.inspect(audit.restart(), codec.encode(snapshot), now)
        assertNotNull(result)
        assertFalse(result.needsResetForStaleEvidence)
    }

    @Test fun futureEvidenceIsFlaggedWithoutStateMutation() {
        val snapshot = BoostV5StateSnapshotCodec.Snapshot(
            meal = BoostV5MealStateDiagnostics.State(
                committedInSession = true, lastAgeMs = now + 31 * 60_000L
            )
        )
        val restored = audit.inspect(audit.restart(), codec.encode(snapshot), now)
        assertNotNull(restored)
        assertTrue(restored.needsResetForStaleEvidence)
        assertTrue(restored.snapshot.meal.committedInSession)
    }

    @Test fun unsupportedFutureSchemaRestoresDefaultWithoutCommitting() {
        val old = codec.encode(BoostV5StateSnapshotCodec.Snapshot())!!
        val restored = audit.inspect(audit.restart(), old + ("newLock" to "true"), now)
        assertNotNull(restored)
        assertEquals(BoostV5StateContinuityDiagnostics.Source.REJECTED_PERSISTED, restored.source)
        assertFalse(restored.snapshot.meal.committedInSession)
    }
}
