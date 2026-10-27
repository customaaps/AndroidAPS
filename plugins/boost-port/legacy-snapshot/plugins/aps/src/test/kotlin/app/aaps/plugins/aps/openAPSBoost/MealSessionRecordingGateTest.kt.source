package app.aaps.plugins.aps.openAPSBoost

import app.aaps.plugins.aps.openAPSBoost.SleepStateDetector.SleepState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Audit #8: learned meal sessions must not be acquired while sleeping or overnight. */
class MealSessionRecordingGateTest {

    @Test fun `daytime awake session may enter the meal-time learner`() {
        assertThat(mealSessionRecordable(false, SleepState.AWAKE)).isTrue()
    }

    @Test fun `night window rejects session even if detector says awake`() {
        assertThat(mealSessionRecordable(true, SleepState.AWAKE)).isFalse()
    }

    @Test fun `sleep and presleep cannot teach the daytime meal-time learner`() {
        assertThat(mealSessionRecordable(false, SleepState.SLEEPING)).isFalse()
        assertThat(mealSessionRecordable(false, SleepState.PRE_SLEEP)).isFalse()
    }
}
