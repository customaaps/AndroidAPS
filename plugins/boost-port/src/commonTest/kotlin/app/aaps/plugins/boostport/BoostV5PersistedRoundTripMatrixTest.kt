package app.aaps.plugins.boostport

import app.aaps.plugins.boostport.telemetry.BoostV5MealStateDiagnostics
import app.aaps.plugins.boostport.telemetry.BoostV5OfflineStateClockAudit
import app.aaps.plugins.boostport.telemetry.BoostV5StateSnapshotCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Caller-fed offline storage parity only. No Android Preferences or live APS. */
class BoostV5PersistedRoundTripMatrixTest {
    @Test fun everyLegacyMealPhasePreservesSessionAndPrimerState() {
        for (phase in BoostV5MealStateDiagnostics.Phase.entries) {
            val snapshot = BoostV5StateSnapshotCodec.Snapshot(
                meal = BoostV5MealStateDiagnostics.State(
                    phase = phase, ageCycles = 3, peakScore = 0.61, peakOffset = 31.5,
                    committedInSession = true, lastAgeMs = 120_000L,
                    lastCommitMs = 60_000L, nonPositiveStartMs = 90_000L,
                    peakScoreAtMs = 110_000L, peakOffsetAtMs = 115_000L,
                ),
                mlMealLikelyNullStreak = 2,
                primerAppliedU = 0.2, primerNettingResidualU = -0.1,
                primerIobU = 0.3, primerIobUpdatedMs = 118_000L,
            )
            val encoded = assertNotNull(BoostV5StateSnapshotCodec.encode(snapshot))
            assertEquals(snapshot, BoostV5StateSnapshotCodec.decode(encoded))
            assertEquals(
                BoostV5OfflineStateClockAudit.Finding.CONSISTENT,
                BoostV5OfflineStateClockAudit.inspect(120_000L, snapshot).finding,
            )
        }
    }

    @Test fun malformedClockCannotPassThroughRestartCodec() {
        val fields = assertNotNull(BoostV5StateSnapshotCodec.encode(BoostV5StateSnapshotCodec.Snapshot()))
        assertNull(BoostV5StateSnapshotCodec.decode(fields + ("lastCommitMs" to "-1")))
        assertNull(BoostV5StateSnapshotCodec.decode(fields + ("primerIobUpdatedMs" to "not-a-time")))
        assertNull(BoostV5StateSnapshotCodec.decode(fields + ("mealHypothesisAge" to "2147483648")))
    }

    @Test fun futureSessionLockClockIsDiagnosedNotSilentlyCleared() {
        val state = BoostV5StateSnapshotCodec.Snapshot(
            meal = BoostV5MealStateDiagnostics.State(
                committedInSession = true, lastCommitMs = 200_000L,
            ),
        )
        assertEquals(
            setOf("lastCommitMs"),
            BoostV5OfflineStateClockAudit.inspect(100_000L, state).futureFields,
        )
    }
}
