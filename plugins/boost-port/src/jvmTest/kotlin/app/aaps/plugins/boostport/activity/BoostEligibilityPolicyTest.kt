package app.aaps.plugins.boostport.activity

import app.aaps.plugins.aps.openAPSBoost.StepFeed
import app.aaps.plugins.aps.openAPSBoost.activity.BoostEligibilityPolicy as Port
import app.aaps.plugins.aps.openAPSBoost.activity.SleepStateDetector
import app.aaps.plugins.boostport.reference.activity.BoostEligibilityPolicy as Source
import app.aaps.plugins.boostport.reference.activity.SleepStateDetector as SourceSleep
import app.aaps.plugins.boostport.reference.activity.StepFeed as SourceFeed
import kotlin.test.*

class BoostEligibilityPolicyTest {
    @Test fun gateAndBoundarySignalsMatchPinnedSourceForEveryModeAndClockBoundary() {
        val now = 1_700_000_000_000L
        for (state in SleepStateDetector.SleepState.entries) for (night in listOf(false, true))
            for (enabled in listOf(false, true)) for (auto in listOf(false, true))
                for (age in listOf<Long?>(null, -1, 0, 14 * 60_000L, 15 * 60_000L)) {
                    val boundary = age?.let { now - it }
                    val actual = Port.sleepSignals(state, night, enabled, auto, now, boundary, Port.boundaryExitHoldMin(10))
                    val expected = Source.sleepSignals(SourceSleep.SleepState.valueOf(state.name), night,
                        enabled, auto, now, boundary, Source.boundaryExitHoldMin(10))
                    assertEquals(expected.toString(), actual.toString())
                    for (window in listOf(false, true)) for (v6 in listOf(false, true))
                        assertEquals(Source.boostGateOpen(expected.nightSleepPeriod, window, v6, expected.detectorSleeping),
                            Port.boostGateOpen(actual.nightSleepPeriod, window, v6, actual.detectorSleeping))
                }
    }
    @Test fun genuineWakeEndsOnlyTheLieInBackstopAndNeverReopensTheClockNight() {
        for (sleepIn in listOf(false, true)) for (enabled in listOf(false, true))
            for (auto in listOf(false, true)) for (sleeping in listOf(false, true)) for (wake in listOf(false, true))
                assertEquals(SourceFeed.lieInFailsafeEngages(sleepIn, enabled, auto, sleeping, wake),
                    StepFeed.lieInFailsafeEngages(sleepIn, enabled, auto, sleeping, wake))
        assertFalse(StepFeed.lieInFailsafeEngages(true, false, false, false, true))
        assertTrue(StepFeed.lieInFailsafeEngages(true, false, true, true, false))
        assertFalse(Port.boostGateOpen(false, true, true, false))
    }
}
