package app.aaps.plugins.aps.openAPSBoost

import app.aaps.plugins.aps.openAPSBoost.SleepStateDetector.SleepState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PreMealTargetSafetyTest {
    @Test fun `daytime awake without blockers can adjust target`() {
        assertThat(preMealTargetBlock(false, SleepState.AWAKE, false, false)).isNull()
    }

    @Test fun `night blocks premeal target`() {
        assertThat(preMealTargetBlock(true, SleepState.AWAKE, false, false)).isEqualTo("night window")
    }

    @Test fun `sleep and pre sleep block premeal target`() {
        assertThat(preMealTargetBlock(false, SleepState.SLEEPING, false, false)).isEqualTo("asleep")
        assertThat(preMealTargetBlock(false, SleepState.PRE_SLEEP, false, false)).isEqualTo("asleep")
    }

    @Test fun `user temp target takes precedence`() {
        assertThat(preMealTargetBlock(false, SleepState.AWAKE, true, false)).isEqualTo("temp target")
    }

    @Test fun `post rescue window blocks anticipatory lowering`() {
        assertThat(preMealTargetBlock(false, SleepState.AWAKE, false, true)).isEqualTo("post-rescue")
    }
}
