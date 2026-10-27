package app.aaps.plugins.aps.openAPSBoost

import app.aaps.plugins.aps.openAPSBoost.OpenAPSBoostPlugin.Companion.boostNightGateOpen
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class BoostNightGateTest {

    @Test fun `the configured night window closes Boost independent of toggle`() {
        assertThat(boostNightGateOpen(inNightWindow = true, detectorAsleep = false)).isFalse()
        assertThat(boostNightGateOpen(inNightWindow = true, detectorAsleep = true)).isFalse()
    }

    @Test fun `detected sleep closes Boost outside the window`() {
        assertThat(boostNightGateOpen(inNightWindow = false, detectorAsleep = true)).isFalse()
    }

    @Test fun `awake daytime leaves Boost available for other gates`() {
        assertThat(boostNightGateOpen(inNightWindow = false, detectorAsleep = false)).isTrue()
    }
    // Adapted from Boost/dev V6StandDownTest: night window and detector must
    // independently prevent Boost even when the night-mode toggle is disabled.
    @Test fun `night window always closes Boost regardless of detector state`() {
        for (asleep in listOf(false, true)) {
            assertThat(boostNightGateOpen(inNightWindow = true, detectorAsleep = asleep)).isFalse()
        }
    }

    @Test fun `night boundary reopens only when both conditions clear`() {
        assertThat(boostNightGateOpen(inNightWindow = true, detectorAsleep = false)).isFalse()
        assertThat(boostNightGateOpen(inNightWindow = false, detectorAsleep = true)).isFalse()
        assertThat(boostNightGateOpen(inNightWindow = false, detectorAsleep = false)).isTrue()
    }

}
