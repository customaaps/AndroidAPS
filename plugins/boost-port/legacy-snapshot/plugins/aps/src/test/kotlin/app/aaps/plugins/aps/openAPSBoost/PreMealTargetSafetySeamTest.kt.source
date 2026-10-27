package app.aaps.plugins.aps.openAPSBoost

import app.aaps.plugins.aps.openAPSBoost.SleepStateDetector.SleepState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Source Boost/dev seam safety audit item #8, using Dmitry's package-level preMealTargetBlock. */
class PreMealTargetSafetySeamTest {

    // ── #8 pre-meal target ──

    @Test fun `pre-meal target - applies only outside the night, awake, no temp target, outside post-rescue`() {
        assertThat(preMealTargetBlock(false, SleepState.AWAKE, false, false)).isNull()
    }

    @Test fun `pre-meal target - blocked inside the night window, asleep, under a temp target and post-rescue`() {
        assertThat(preMealTargetBlock(true, SleepState.AWAKE, false, false)).isEqualTo("night window")
        assertThat(preMealTargetBlock(false, SleepState.SLEEPING, false, false)).isEqualTo("asleep")
        assertThat(preMealTargetBlock(false, SleepState.PRE_SLEEP, false, false)).isEqualTo("asleep")
        assertThat(preMealTargetBlock(false, SleepState.AWAKE, true, false)).isEqualTo("temp target")
        assertThat(preMealTargetBlock(false, SleepState.AWAKE, false, true)).isEqualTo("post-rescue")
    }

}
