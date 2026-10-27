package app.aaps.plugins.aps.openAPSBoost

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class V6FallbackSmbSafetyTest {
    @Test fun `V6 failure clamps amplified V1 dose`() {
        assertThat(v6UnavailableSmb(2.0, 0.4)).isEqualTo(0.4)
        assertThat(v6UnavailableSmb(0.2, 0.4)).isEqualTo(0.2)
    }

    @Test fun `missing original dose remains missing`() {
        assertThat(v6UnavailableSmb(null, 0.4)).isNull()
    }

    @Test fun `base oref limit respects basal minute ceiling`() {
        val result = baseOrefSmb(3.0, 1.0, 1.2, 15, 30, 100.0, 0.05)
        assertThat(result).isWithin(1e-9).of(0.3)
    }

    @Test fun `observed V6 fallback field case caps tier dose`() {
        val base = baseOrefSmb(1.06, 0.278, 0.6, 20, 20, 85.0, 0.05)
        assertThat(base).isWithin(1e-9).of(0.2)
        assertThat(v6UnavailableSmb(1.95, base)).isWithin(1e-9).of(0.2)
    }

    @Test fun `small correction is floored to pump increment`() {
        assertThat(baseOrefSmb(0.5, 0.0, 1.5, 30, 30, 85.0, 0.05))
            .isWithin(1e-9).of(0.4)
    }

    @Test fun `negative IOB selects non UAM basal minutes`() {
        assertThat(baseOrefSmb(2.0, -0.5, 1.0, 60, 18, 100.0, 0.05))
            .isWithin(1e-9).of(0.3)
    }

    @Test fun `no insulin requirement means no fallback SMB`() {
        assertThat(baseOrefSmb(0.0, 0.0, 1.0, 30, 30, 85.0, 0.05)).isEqualTo(0.0)
        assertThat(baseOrefSmb(-0.4, 0.0, 1.0, 30, 30, 85.0, 0.05)).isEqualTo(0.0)
    }

    @Test fun `invalid inputs produce no fallback bolus`() {
        assertThat(baseOrefSmb(3.0, 0.0, 1.0, 15, 30, 100.0, 0.0)).isEqualTo(0.0)
        assertThat(baseOrefSmb(Double.NaN, 0.0, 1.0, 15, 30, 100.0, 0.05)).isEqualTo(0.0)
    }
}
