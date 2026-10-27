package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoostV5StateContinuityDiagnosticsTest {
    private val audit = BoostV5StateContinuityDiagnostics
    private val codec = BoostV5StateSnapshotCodec
    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    private fun snapshot(
        phase: BoostV5MealStateDiagnostics.Phase = BoostV5MealStateDiagnostics.Phase.OBSERVING,
        lastAgeMs: Long = now,
        locked: Boolean = false,
    ) = BoostV5StateSnapshotCodec.Snapshot(
        meal = BoostV5MealStateDiagnostics.State(
            phase = phase, ageCycles = 2, peakScore = 0.7,
            peakOffset = 50.0, committedInSession = locked,
            lastAgeMs = lastAgeMs,
            lastCommitMs = if (locked) now - 10 * minute else 0L,
        ),
        mlMealLikelyNullStreak = 2,
    )

    @Test fun coldStartWithNoPersistedStateReportsIdle() {
        val inspection = audit.inspect(BoostV5StateContinuityDiagnostics.Memory(), null, now)!!
        assertEquals(BoostV5StateContinuityDiagnostics.Source.DEFAULT_EMPTY, inspection.source)
        assertEquals(BoostV5MealStateDiagnostics.Phase.IDLE, inspection.snapshot.meal.phase)
        assertFalse(inspection.needsResetForStaleEvidence)
    }

    @Test fun memoryWinsOverStalePersistedState() {
        val oldDisk = codec.encode(snapshot(phase = BoostV5MealStateDiagnostics.Phase.IDLE))!!
        val freshCache = snapshot(phase = BoostV5MealStateDiagnostics.Phase.CONFIRMED,
            locked = true)
        val memory = audit.stageSave(BoostV5StateContinuityDiagnostics.Memory(), freshCache)!!
        val selected = audit.inspect(memory, oldDisk, now)!!
        assertEquals(BoostV5StateContinuityDiagnostics.Source.MEMORY, selected.source)
        assertEquals(BoostV5MealStateDiagnostics.Phase.CONFIRMED, selected.snapshot.meal.phase)
        assertTrue(selected.snapshot.meal.committedInSession)
    }

    @Test fun rapidSecondSaveIsVisibleBeforePreferenceFlush() {
        val first = snapshot(phase = BoostV5MealStateDiagnostics.Phase.OBSERVING)
        val second = snapshot(phase = BoostV5MealStateDiagnostics.Phase.CONFIRMED,
            locked = true)
        val onDisk = codec.encode(first)!!
        val memory = audit.stageSave(audit.stageSave(BoostV5StateContinuityDiagnostics.Memory(), first)!!, second)!!
        val inspection = audit.inspect(memory, onDisk, now)!!
        assertEquals(second, inspection.snapshot)
    }

    @Test fun restartDropsOnlyVolatileCacheNotCommittedPersistedState() {
        val prior = snapshot(phase = BoostV5MealStateDiagnostics.Phase.RECOVERING,
            locked = true)
        val onDisk = codec.encode(prior)!!
        val memory = audit.stageSave(BoostV5StateContinuityDiagnostics.Memory(), prior)!!
        assertNotNull(memory.current)
        val recovered = audit.inspect(audit.restart(), onDisk, now)!!
        assertEquals(BoostV5StateContinuityDiagnostics.Source.PERSISTED, recovered.source)
        assertEquals(prior, recovered.snapshot)
        assertTrue(recovered.snapshot.meal.committedInSession)
    }

    @Test fun stalePersistedEvidenceReportsResetButDoesNotDiscardLock() {
        val record = snapshot(
            phase = BoostV5MealStateDiagnostics.Phase.RECOVERING,
            lastAgeMs = now - 35 * minute,
            locked = true,
        )
        val selected = audit.inspect(audit.restart(), codec.encode(record), now)!!
        assertTrue(selected.needsResetForStaleEvidence)
        assertEquals(35.0, selected.ageMinutes)
        assertTrue(selected.snapshot.meal.committedInSession)
        assertEquals(record.meal.lastCommitMs, selected.snapshot.meal.lastCommitMs)
    }

    @Test fun thirtyMinutesIsNotPastTheLegacyResetThreshold() {
        val selected = audit.inspect(audit.restart(),
            codec.encode(snapshot(lastAgeMs = now - 30 * minute)), now)!!
        assertFalse(selected.needsResetForStaleEvidence)
        assertEquals(30.0, selected.ageMinutes)
    }

    @Test fun corruptPersistenceReportsRejectionInsteadOfMakingUpAnActiveMeal() {
        val broken = codec.encode(snapshot())!! + ("mealHypothesisAge" to "bad")
        val selected = audit.inspect(audit.restart(), broken, now)!!
        assertEquals(BoostV5StateContinuityDiagnostics.Source.REJECTED_PERSISTED, selected.source)
        assertEquals(BoostV5MealStateDiagnostics.Phase.IDLE, selected.snapshot.meal.phase)
    }

    @Test fun corruptMemoryNeverFallsThroughToDisk() {
        val corrupt = snapshot().copy(mlMealLikelyNullStreak = -1)
        val disk = codec.encode(snapshot())!!
        assertNull(audit.inspect(BoostV5StateContinuityDiagnostics.Memory(corrupt), disk, now))
        assertNull(audit.stageSave(BoostV5StateContinuityDiagnostics.Memory(), corrupt))
    }

    @Test fun unknownAndNegativeNowAreNotTreatedAsValidEvidenceClock() {
        assertNull(audit.inspect(BoostV5StateContinuityDiagnostics.Memory(), null, -1L))
        assertNull(audit.inspect(BoostV5StateContinuityDiagnostics.Memory(), null, 0L)!!.ageMinutes)
    }

    @Test fun reversingWallClockIsReportedAsStaleByAbsoluteAge() {
        val record = snapshot(lastAgeMs = now + 40 * minute)
        val selected = audit.inspect(BoostV5StateContinuityDiagnostics.Memory(),
            codec.encode(record), now)!!
        assertEquals(40.0, selected.ageMinutes)
        assertTrue(selected.needsResetForStaleEvidence)
    }
}
