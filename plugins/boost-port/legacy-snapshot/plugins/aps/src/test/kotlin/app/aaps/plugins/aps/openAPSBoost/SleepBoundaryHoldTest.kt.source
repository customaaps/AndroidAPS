package app.aaps.plugins.aps.openAPSBoost

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SleepBoundaryHoldTest {
    private val minute = 60_000L
    @Test fun `boundary wake remains guarded through the next cycle`() {
        assertThat(sleepBoundaryHoldActive(1_000_000L + 5 * minute, 1_000_000L, 10)).isTrue()
    }
    @Test fun `hold ends after hysteresis and one cycle`() {
        assertThat(sleepBoundaryHoldActive(1_000_000L + 15 * minute, 1_000_000L, 10)).isFalse()
    }
    @Test fun `no boundary wake or backwards time does not create hold`() {
        assertThat(sleepBoundaryHoldActive(1_000_000L, null, 10)).isFalse()
        assertThat(sleepBoundaryHoldActive(1_000_000L, 1_100_000L, 10)).isFalse()
    }
    @Test fun `boundary hold prevents inactivity insulin raise despite low steps`() {
        val boundaryExit = 1_000_000L
        val asleep = sleepBoundaryHoldActive(boundaryExit + 5 * minute, boundaryExit, 10)
        assertThat(asleep).isTrue()
        assertThat(StepFeed.inactivityEligible(
            stepsAvailable = true,
            currentProfileSwitch = 100,
            recentSteps60Min = 0,
            inactivitySteps = 400,
            sleepInActive = false,
            asleep = asleep,
            inNightWindow = false
        )).isFalse()
        assertThat(StepFeed.inactivityEligible(
            stepsAvailable = true,
            currentProfileSwitch = 100,
            recentSteps60Min = 0,
            inactivitySteps = 400,
            sleepInActive = false,
            asleep = false,
            inNightWindow = false
        )).isTrue()
    }
}
