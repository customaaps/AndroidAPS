package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.StepFeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StepFeedPortTest {
    @Test fun wearFreshnessBoundaryDoesNotRequireAndroidWearSource() {
        val fresh = StepFeed.State(phoneLive = false, wearAgeMs = StepFeed.WEAR_FRESH_MS)
        assertTrue(fresh.wearFresh)
        assertTrue(fresh.available)
        assertEquals("wear", fresh.label)

        val stale = StepFeed.State(phoneLive = false, wearAgeMs = StepFeed.WEAR_FRESH_MS + 1L)
        assertFalse(stale.wearFresh)
        assertFalse(stale.available)
        assertEquals("none", stale.label)
    }

    @Test fun missingStepFeedCannotTriggerInactivityOrLieIn() {
        assertFalse(StepFeed.inactivityEligible(
            stepsAvailable = false, currentProfileSwitch = 100, recentSteps60Min = 0,
            inactivitySteps = 100, sleepInActive = false, asleep = false, inNightWindow = false
        ))
        assertFalse(StepFeed.sleepInActive(
            stepsAvailable = false, nowMs = 10_000L, nightEndMs = 0L, sleepInMs = 20_000L,
            recentSteps60Min = 0, sleepInSteps = 100
        ))
    }

    @Test fun sleepOrNightExcludesInactivityEvenWithLiveSteps() {
        for (flags in listOf(Triple(true, false, false), Triple(false, true, false), Triple(false, false, true))) {
            assertFalse(StepFeed.inactivityEligible(
                true, 100, 0, 100, flags.first, flags.second, flags.third
            ))
        }
    }

    @Test fun negativeRollingStepCountDoesNotMeanInactive() {
        assertFalse(StepFeed.inactivityStepsMet(true, 100, -1, 100))
        assertFalse(StepFeed.inactivityStepsMet(true, 100, 0, 0))
    }

    @Test fun invalidRollingStepsCannotActivateSleepInFailsafe() {
        assertFalse(StepFeed.sleepInActive(true, 10_000L, 0L, 20_000L, -1, 100))
        assertFalse(StepFeed.sleepInActive(true, 10_000L, 0L, 20_000L, 0, 0))
    }

    @Test fun lieInWindowHandlesLongTimestampBoundary() {
        assertTrue(StepFeed.sleepInActive(true, Long.MAX_VALUE - 1L, Long.MAX_VALUE - 2L, 10L, 0, 100))
        assertFalse(StepFeed.sleepInActive(true, Long.MAX_VALUE - 1L, Long.MAX_VALUE - 2L, 0L, 0, 100))
    }

    @Test fun futureDatedWearEventIsNotCountedAsLive() {
        val future = StepFeed.State(phoneLive = false, wearAgeMs = -60_000L)
        assertFalse(future.wearFresh)
        assertFalse(future.available)
        assertEquals("none", future.label)
        val phone = StepFeed.State(phoneLive = true, wearAgeMs = -60_000L)
        assertTrue(phone.available)
        assertEquals("phone", phone.label)
    }
}
