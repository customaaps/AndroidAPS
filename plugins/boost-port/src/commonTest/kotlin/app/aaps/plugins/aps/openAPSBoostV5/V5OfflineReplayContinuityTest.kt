package app.aaps.plugins.aps.openAPSBoostV5

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class V5OfflineReplayContinuityTest {
    private fun fields(locked: Boolean, ageMs: Long, commitMs: Long): Map<String, String> =
        requireNotNull(V5OfflineStateHandoff.export(
            V5PersistedState(
                mealHypothesis = MealHypothesisState(
                    state = MealHypothesis.COMMITTED,
                    committedInSession = locked,
                    lastAgeMs = ageMs,
                    lastCommitMs = commitMs
                )
            )
        ))

    @Test fun invalidStateStopsAtItsOwnIndex() {
        val result = V5OfflineReplayContinuity.inspect(
            listOf(fields(true, 1000, 1000), mapOf("invalid" to "1"))
        )
        assertEquals(V5OfflineReplayContinuity.Finding.MISSING_OR_INVALID_SNAPSHOT, result.finding)
        assertEquals(1, result.rejectedIndex)
        assertFalse(result.insulinDeliveryAllowed)
    }

    @Test fun decreasingAgeClockIsDiagnosed() {
        val result = V5OfflineReplayContinuity.inspect(
            listOf(fields(true, 2000, 1000), fields(true, 1500, 1000))
        )
        assertEquals(V5OfflineReplayContinuity.Finding.CLOCK_REGRESSION, result.finding)
        assertEquals(1, result.rejectedIndex)
        assertFalse(result.liveInvocationAllowed)
    }

    @Test fun disappearingUnchangedCommitLockIsDiagnosed() {
        val result = V5OfflineReplayContinuity.inspect(
            listOf(fields(true, 2000, 1000), fields(false, 3000, 1000))
        )
        assertEquals(V5OfflineReplayContinuity.Finding.SESSION_LOCK_LOST, result.finding)
        assertEquals(1, result.rejectedIndex)
        assertFalse(result.insulinDeliveryAllowed)
    }

    @Test fun monotonicSnapshotsCanBeInspectedWithoutEnablingDelivery() {
        val result = V5OfflineReplayContinuity.inspect(
            listOf(fields(true, 2000, 1000), fields(true, 3000, 2000))
        )
        assertEquals(V5OfflineReplayContinuity.Finding.OFFLINE_CONTINUITY_DESCRIBED, result.finding)
        assertEquals(2, result.inspectedFrames)
        assertFalse(result.liveInvocationAllowed)
        assertFalse(result.insulinDeliveryAllowed)
    }
}
