package app.aaps.plugins.aps.openAPSBoost

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PrimerTempSafetyTest {
    @Test fun `running protective zero temp cannot be ignored`() {
        assertThat(effectiveBaseTempRate(null, 0.0, 20)).isEqualTo(0.0)
    }
    @Test fun `without running temp a missing base rate remains missing`() {
        assertThat(effectiveBaseTempRate(null, 0.0, 0)).isNull()
    }
    @Test fun `explicit base engine rate takes precedence`() {
        assertThat(effectiveBaseTempRate(0.3, 2.5, 20)).isEqualTo(0.3)
    }
    @Test fun `existing high temp is retained when no new base rate is proposed`() {
        assertThat(effectiveBaseTempRate(null, 2.4, 15)).isEqualTo(2.4)
    }

    @Test fun `ongoing low temp is retained instead of primer even when above zero`() {
        assertThat(effectiveBaseTempRate(null, 0.3, 20)).isEqualTo(0.3)
    }
}
