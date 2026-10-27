package app.aaps.plugins.aps.openAPSBoost

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class BoostTddSettingTransitionTest {
    @Test fun `TDD off disables adjustment and BG sensitivity velocity`() {
        val r = boostTddSettingsTransition(false, true, 100.0, false)
        assertThat(r.adjustSens).isFalse()
        assertThat(r.velocityPct).isEqualTo(0.0)
        assertThat(r.clearedAdjustSens).isTrue()
        assertThat(r.zeroedVelocity).isTrue()
    }

    @Test fun `switch to V6 clears adjustment without changing active TDD velocity`() {
        val r = boostTddSettingsTransition(true, true, 40.0, true)
        assertThat(r.adjustSens).isFalse()
        assertThat(r.velocityPct).isEqualTo(40.0)
    }

    @Test fun `TDD enabled transition restores default BG impact`() {
        val r = boostTddSettingsTransition(true, false, 0.0, false, tddJustEnabled = true)
        assertThat(r.velocityPct).isEqualTo(100.0)
        assertThat(r.restoredVelocity).isTrue()
    }

    @Test fun `unchanged enabled TDD retains user selected impact`() {
        val r = boostTddSettingsTransition(true, false, 40.0, false)
        assertThat(r.velocityPct).isEqualTo(40.0)
        assertThat(r.changed).isFalse()
    }

    @Test fun `only recorded disabled to enabled edge triggers restore`() {
        assertThat(boostTddJustEnabled("false", true)).isTrue()
        assertThat(boostTddJustEnabled("", true)).isFalse()
        assertThat(boostTddJustEnabled("true", true)).isFalse()
        assertThat(boostTddJustEnabled("false", false)).isFalse()
    }
    /** Additional source BoostSensitivitySettingsReconcileTest parity, adapted to Dmitry helper. */
    @Test fun `TDD off with already-safe settings is unchanged`() {
        val r = boostTddSettingsTransition(false, false, 0.0, false)
        assertThat(r.changed).isFalse()
    }

    @Test fun `enabled TDD under V1 keeps user adjustment`() {
        val r = boostTddSettingsTransition(true, true, 60.0, false)
        assertThat(r.changed).isFalse()
        assertThat(r.adjustSens).isTrue()
        assertThat(r.velocityPct).isEqualTo(60.0)
    }

    @Test fun `enabling TDD cannot enable an adjustment disabled by user`() {
        val r = boostTddSettingsTransition(true, false, 0.0, false, tddJustEnabled = true)
        assertThat(r.adjustSens).isFalse()
    }

    @Test fun `without a new TDD switch settings can only become less aggressive`() {
        for (tdd in listOf(true, false))
            for (adjust in listOf(true, false))
                for (switched in listOf(true, false))
                    for (velocity in listOf(0.0, 50.0, 100.0)) {
                        val r = boostTddSettingsTransition(tdd, adjust, velocity, switched)
                        if (!adjust) assertThat(r.adjustSens).isFalse()
                        assertThat(r.velocityPct).isAtMost(velocity)
                    }
    }

    @Test fun `V6 transition happens from V1 or an uninitialized mode only`() {
        assertThat(boostSwitchedToV6("v1", true)).isTrue()
        assertThat(boostSwitchedToV6("", true)).isTrue()
        assertThat(boostSwitchedToV6("v6", true)).isFalse()
        assertThat(boostSwitchedToV6("v6", false)).isFalse()
        assertThat(boostSwitchedToV6("v1", false)).isFalse()
    }

}
