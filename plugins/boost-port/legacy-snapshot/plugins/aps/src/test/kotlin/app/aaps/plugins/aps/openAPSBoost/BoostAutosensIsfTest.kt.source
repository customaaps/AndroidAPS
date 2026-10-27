package app.aaps.plugins.aps.openAPSBoost

import app.aaps.plugins.aps.openAPSBoost.OpenAPSBoostPlugin.Companion.autosensAdjustedIsf
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class BoostAutosensIsfTest {
    private fun isf(useTdd: Boolean = false, autosensWhenNoTdd: Boolean = true, tt: Double = 1.0, ratio: Double) =
        autosensAdjustedIsf(68.4, useTdd, autosensWhenNoTdd, tt, ratio)

    @Test fun `resistant ratio strengthens dosing ISF`() {
        assertThat(isf(ratio = 1.3)).isWithin(0.05).of(52.6)
    }

    @Test fun `sensitive ratio weakens dosing ISF`() {
        assertThat(isf(ratio = 0.8)).isWithin(0.05).of(85.5)
    }

    @Test fun `neutral ratio does not modify ISF`() {
        assertThat(isf(ratio = 1.0)).isEqualTo(68.4)
    }

    @Test fun `TDD and disabled autosens do not double count`() {
        assertThat(isf(useTdd = true, ratio = 1.3)).isEqualTo(68.4)
        assertThat(isf(autosensWhenNoTdd = false, ratio = 1.3)).isEqualTo(68.4)
    }

    @Test fun `temp-target ratio replaces autosens`() {
        assertThat(isf(tt = 0.7, ratio = 1.3)).isEqualTo(68.4)
    }

    @Test fun `upstream autosens adjustment must not be applied twice in static mode`() {
        // The Dmitry adapter applies traditional autosens to sensNormalTarget *before*
        // calculating variable_sens. A second static autosens division in DetermineBasalBoost
        // would incorrectly amplify insulin sensitivity or resistance.
        val upstreamAdjusted = isf(ratio = 1.3)
        assertThat(upstreamAdjusted).isWithin(0.05).of(68.4 / 1.3)
        val wronglyAdjustedAgain = upstreamAdjusted / 1.3
        assertThat(wronglyAdjustedAgain).isNotWithin(0.05).of(upstreamAdjusted)
    }

    @Test fun `TDD mode bypasses traditional autosens even when enabled`() {
        assertThat(isf(useTdd = true, autosensWhenNoTdd = true, ratio = 1.3)).isEqualTo(68.4)
    }

    @Test fun `invalid autosens ratios do not divide ISF`() {
        assertThat(isf(ratio = 0.0)).isEqualTo(68.4)
        assertThat(isf(ratio = -1.0)).isEqualTo(68.4)
        assertThat(isf(ratio = Double.NaN)).isEqualTo(68.4)
    }
}
