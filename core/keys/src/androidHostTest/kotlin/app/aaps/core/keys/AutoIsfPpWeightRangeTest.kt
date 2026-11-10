package app.aaps.core.keys

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Protects the user-editable post-meal AutoISF weight range and its slider precision. */
class AutoIsfPpWeightRangeTest {
    @Test
    fun postMealWeightHasOneHundredStepsBetweenZeroAndOne() {
        val key = DoubleKey.ApsAutoIsfPpWeight
        assertThat(key.min).isEqualTo(0.0)
        assertThat(key.max).isEqualTo(1.0)
        assertThat(key.stepSize).isEqualTo(0.01)
        assertThat(key.unitType.decimalPlaces()).isEqualTo(2)
        assertThat(key.defaultValue).isEqualTo(0.0)
    }
}
