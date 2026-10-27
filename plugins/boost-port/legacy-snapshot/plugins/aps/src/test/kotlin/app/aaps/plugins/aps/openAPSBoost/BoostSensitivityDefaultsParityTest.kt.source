package app.aaps.plugins.aps.openAPSBoost

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Boost/dev BoostSensitivitySettingsReconcileTest contains an assertion that
 * ApsBoostAutosensWhenNoTdd defaults to true. Dmitry currently defaults to
 * false: this discrepancy is intentional to preserve existing dosing behavior
 * until the integration is reviewed. Do not silently copy the source default.
 */
class BoostSensitivityDefaultsParityTest {

    @Test fun `Dmitry preserves opt-in autosens without TDD pending reconciliation`() {
        assertThat(BooleanKey.ApsBoostAutosensWhenNoTdd.defaultValue).isFalse()
    }

    @Test fun `Dmitry retains existing sensitivity adjustment defaults`() {
        assertThat(BooleanKey.ApsBoostAdjustSensitivity.defaultValue).isFalse()
        assertThat(DoubleKey.ApsBoostDynIsfVelocity.defaultValue).isEqualTo(100.0)
    }
    @Test fun `TDD-only sensitivity controls depend on TDD mode`() {
        assertThat(BooleanKey.ApsBoostAdjustSensitivity.dependency).isEqualTo(BooleanKey.ApsBoostUseTdd)
        assertThat(DoubleKey.ApsBoostDynIsfVelocity.dependency).isEqualTo(BooleanKey.ApsBoostUseTdd)
    }

}
