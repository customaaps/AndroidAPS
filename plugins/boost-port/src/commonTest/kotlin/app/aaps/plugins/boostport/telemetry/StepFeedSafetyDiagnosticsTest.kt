package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StepFeedSafetyDiagnosticsTest {
    private val d = StepFeedSafetyDiagnostics
    private val end = 1_000_000_000L
    private val hour = 3_600_000L
    private val fresh = StepFeedSafetyDiagnostics.WEAR_FRESH_MS

    private fun context(
        feed: StepFeedSafetyDiagnostics.Feed = StepFeedSafetyDiagnostics.Feed(
            phoneReportedThisBoot = true, latestWearAgeMs = null),
        steps: Int? = 0,
        profilePct: Int = 100,
        inactiveLimit: Int = 200,
        asleep: Boolean = false,
        inNight: Boolean = false,
        nightEnabled: Boolean = true,
        automaticSleep: Boolean = true,
        detectorSleeping: Boolean = false,
        now: Long = end - hour,
        lieInHours: Int = 2,
        sleepInLimit: Int = 75,
    ) = StepFeedSafetyDiagnostics.Context(
        feed = feed,
        currentProfileSwitchPct = profilePct,
        observedStepsLast60Min = steps,
        inactivityStepsThreshold = inactiveLimit,
        currentlyAsleepOrPreSleep = asleep,
        inConfiguredNightClock = inNight,
        nightModeEnabled = nightEnabled,
        autoBySleepActive = automaticSleep,
        detectorSleeping = detectorSleeping,
        nowMs = now,
        nightEndMs = end,
        sleepInWindowMs = lieInHours * hour,
        sleepInStepsThreshold = sleepInLimit,
    )

    @Test fun bothPhoneAndFreshWearCountAsKnownFeed() {
        val r = d.inspect(context(feed = StepFeedSafetyDiagnostics.Feed(true, 2 * 60_000L)))!!
        assertEquals("phone+wear", r.feedLabel)
        assertTrue(r.feedAvailable)
    }

    @Test fun phoneOnlyAndStaleWearNeverHaveFreshWearLabel() {
        assertEquals("phone", d.inspect(context())!!.feedLabel)
        assertEquals("phone", d.inspect(context(
            feed = StepFeedSafetyDiagnostics.Feed(true, fresh + 1)))!!.feedLabel)
    }

    @Test fun wearOnlyExactlyAtFreshnessBoundaryIsAvailable() {
        val r = d.inspect(context(feed = StepFeedSafetyDiagnostics.Feed(false, fresh)))!!
        assertEquals("wear", r.feedLabel)
        assertTrue(r.feedAvailable)
    }

    @Test fun absentPhoneAndWearAreUnknownEvenWhenWindowShowsZeroSteps() {
        val r = d.inspect(context(feed = StepFeedSafetyDiagnostics.Feed(false, null)))!!
        assertEquals("none", r.feedLabel)
        assertFalse(r.feedAvailable)
        assertFalse(r.inactivityStepsMet)
        assertFalse(r.hypotheticallyInactivityEligible)
        assertEquals("steps:UNAVAILABLE(phone=none-this-boot, wear=none)", r.note)
    }

    @Test fun genuinelyKnownZeroStepsCanSatisfyOfflineInactivityPredicate() {
        val r = d.inspect(context())!!
        assertTrue(r.feedAvailable)
        assertTrue(r.inactivityStepsMet)
        assertTrue(r.hypotheticallyInactivityEligible)
    }

    @Test fun unavailableNumericCountNeverMeansKnownSedentaryActivity() {
        val r = d.inspect(context(steps = null))!!
        assertFalse(r.inactivityStepsMet)
        assertFalse(r.hypotheticallyInactivityEligible)
        assertFalse(r.hypotheticallyInLieIn)
    }

    @Test fun highStepsAndDifferentProfileBlockInactivePredicate() {
        assertFalse(d.inspect(context(steps = 220))!!.hypotheticallyInactivityEligible)
        assertFalse(d.inspect(context(profilePct = 80))!!.hypotheticallyInactivityEligible)
    }

    @Test fun sleepingOrPreSleepDisablesInactivePredicate() {
        assertFalse(d.inspect(context(asleep = true))!!.hypotheticallyInactivityEligible)
    }

    @Test fun clockNightDisablesInactivePredicateRegardlessOfModeSwitch() {
        val r = d.inspect(context(inNight = true, nightEnabled = false))!!
        assertFalse(r.hypotheticallyInactivityEligible)
        assertTrue(r.inactivityStepsMet)
    }

    @Test fun lieInIsHalfOpenAndNeverFiresWithoutLiveSource() {
        val during = d.inspect(context(now = end + hour, steps = 0))!!
        assertTrue(during.hypotheticallyInLieIn)
        assertFalse(during.hypotheticallyInactivityEligible)
        assertFalse(d.inspect(context(now = end + 2 * hour))!!.hypotheticallyInLieIn)
        assertFalse(d.inspect(context(now = end - 1))!!.hypotheticallyInLieIn)
        assertFalse(d.inspect(context(now = end + hour,
            feed = StepFeedSafetyDiagnostics.Feed(false, null)))!!.hypotheticallyInLieIn)
    }

    @Test fun detectorSleepingOnlyDefersFailsafeWhenNightModeActuallyEnabled() {
        val active = d.inspect(context(now = end + hour,
            detectorSleeping = true, nightEnabled = true, automaticSleep = true))!!
        assertFalse(active.hypotheticallyLieInFailsafeNeeded)
        val disabled = d.inspect(context(now = end + hour,
            detectorSleeping = true, nightEnabled = false, automaticSleep = true))!!
        assertTrue(disabled.hypotheticallyLieInFailsafeNeeded)
    }

    @Test fun detectorFalseAwakeInLieInNeedsSeparateShadowSafetyFlag() {
        val r = d.inspect(context(now = end + hour, detectorSleeping = false))!!
        assertTrue(r.hypotheticallyLieInFailsafeNeeded)
    }

    @Test fun falseSensorAgeFromFutureIsRejected() {
        assertNull(d.inspect(context(feed = StepFeedSafetyDiagnostics.Feed(false, -1L))))
    }

    @Test fun corruptThresholdOrCounterDoesNotYieldEligibility() {
        assertNull(d.inspect(context(steps = -1)))
        assertNull(d.inspect(context(inactiveLimit = 0)))
        assertNull(d.inspect(context(sleepInLimit = 0)))
        assertNull(d.inspect(context(profilePct = -1)))
    }

    @Test fun malformedTimeWindowDoesNotOverflow() {
        assertNull(d.inspect(context().copy(nightEndMs = Long.MAX_VALUE,
            sleepInWindowMs = hour)))
        assertNull(d.inspect(context().copy(sleepInWindowMs = -1L)))
    }
}
