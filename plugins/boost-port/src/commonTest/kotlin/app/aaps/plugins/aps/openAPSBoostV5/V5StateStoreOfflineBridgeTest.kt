package app.aaps.plugins.aps.openAPSBoostV5

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class V5StateStoreOfflineBridgeTest {
    @Test fun commitLockAndPrimerStateSurviveRoundTrip() {
        val original = V5PersistedState(
            mealHypothesis = MealHypothesisState(
                state = MealHypothesis.COMMITTED,
                ageCycles = 3,
                committedInSession = true,
                lastCommitMs = 1000L,
            ),
            primerAppliedU = 0.2,
            primerNettingResidualU = 0.1,
            primerIobU = 0.3,
            primerIobUpdatedMs = 1000L,
            lastCycleScore = 0.7,
            scoreReadySinceMs = 1000L,
        )
        val fields = V5StateStoreOfflineBridge.toFields(original)!!
        val restored = V5StateStoreOfflineBridge.fromFields(fields)!!
        assertTrue(restored.mealHypothesis.committedInSession)
        assertEquals(MealHypothesis.COMMITTED, restored.mealHypothesis.state)
        assertEquals(0.3, restored.primerIobU)
        assertNull(restored.lastCycleScore)
        assertEquals(0L, restored.scoreReadySinceMs)
    }

    @Test fun unknownPersistedFieldRejectsRestore() {
        val fields = V5StateStoreOfflineBridge.toFields(V5PersistedState())!!
        assertNull(V5StateStoreOfflineBridge.fromFields(fields + ("unknownLock" to "true")))
    }

    @Test fun damagedLockRejectsRestore() {
        val fields = V5StateStoreOfflineBridge.toFields(V5PersistedState())!!
        assertNull(V5StateStoreOfflineBridge.fromFields(fields + ("committedInSession" to "corrupt")))
    }
}
