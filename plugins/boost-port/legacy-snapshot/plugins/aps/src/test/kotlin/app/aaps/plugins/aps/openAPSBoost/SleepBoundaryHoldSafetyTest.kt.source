package app.aaps.plugins.aps.openAPSBoost

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Adapted from Boost/dev SeamSafetyGatesTest audit item #18.
 * Dmitry uses sleepBoundaryHoldActive to keep the detector-asleep gate engaged
 * during the hysteresis interval after the configured morning boundary.
 * This characterizes the helper without changing the live insulin path.
 */
class SleepBoundaryHoldSafetyTest {
    private val minute = 60_000L
    private val boundaryExit = 1_000_000_000L

    @Test fun `morning boundary keeps sleep protection for hysteresis plus one cycle`() {
        assertThat(sleepBoundaryHoldActive(boundaryExit + 5 * minute, boundaryExit, 10)).isTrue()
        assertThat(sleepBoundaryHoldActive(boundaryExit + 14 * minute, boundaryExit, 10)).isTrue()
    }

    @Test fun `protection expires at the end of the boundary hold`() {
        assertThat(sleepBoundaryHoldActive(boundaryExit + 15 * minute, boundaryExit, 10)).isFalse()
    }

    @Test fun `missing boundary event does not invent a sleep hold`() {
        assertThat(sleepBoundaryHoldActive(boundaryExit, null, 10)).isFalse()
    }

    @Test fun `clock before last exit does not activate a stale hold`() {
        assertThat(sleepBoundaryHoldActive(boundaryExit - minute, boundaryExit, 10)).isFalse()
    }

    @Test fun `negative configured hysteresis cannot make the hold negative`() {
        assertThat(sleepBoundaryHoldActive(boundaryExit + 4 * minute, boundaryExit, -10)).isTrue()
        assertThat(sleepBoundaryHoldActive(boundaryExit + 5 * minute, boundaryExit, -10)).isFalse()
    }
}
