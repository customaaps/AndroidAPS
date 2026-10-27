package app.aaps.plugins.aps.openAPSBoost

import app.aaps.plugins.aps.openAPSBoost.OpenAPSBoostPlugin.Companion.boostNightGateOpen
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ActivityNightGateTest {
    @Test fun `exercise evidence survives closed night gate`() {
        assertThat(boostNightGateOpen(inNightWindow = true, detectorAsleep = false)).isFalse()
        assertThat(boostExerciseStepsMet(200, 400, 800, 1500, 250, 250, 250, 250)).isTrue()
    }

    @Test fun `exercise evidence survives closed sleep gate`() {
        assertThat(boostNightGateOpen(inNightWindow = false, detectorAsleep = true)).isFalse()
        assertThat(boostExerciseStepsMet(200, 400, 800, 1500, 250, 250, 250, 250)).isTrue()
    }

    @Test fun `inactivity eligibility remains closed overnight`() {
        assertThat(StepFeed.inactivityEligible(true, 100, 0, 400, false, false, true)).isFalse()
        assertThat(StepFeed.inactivityEligible(true, 100, 0, 400, false, true, false)).isFalse()
    }

    @Test fun `exercise thresholds retain strict greater than comparison`() {
        assertThat(boostExerciseStepsMet(200, 0, 0, 0, 200, 0, 0, 0)).isFalse()
        assertThat(boostExerciseStepsMet(200, 0, 0, 0, 201, 0, 0, 0)).isTrue()
    }

    @Test fun `night exercise detection also works from longer step windows`() {
        assertThat(boostNightGateOpen(true, false)).isFalse()
        assertThat(boostExerciseStepsMet(200, 400, 800, 1500, 10, 401, 10, 10)).isTrue()
        assertThat(boostExerciseStepsMet(200, 400, 800, 1500, 10, 10, 801, 10)).isTrue()
        assertThat(boostExerciseStepsMet(200, 400, 800, 1500, 10, 10, 10, 1501)).isTrue()
    }

    @Test fun `disabled thresholds and subthreshold steps do not imply exercise`() {
        assertThat(boostExerciseStepsMet(0, 0, 0, 0, 900, 900, 900, 900)).isFalse()
        assertThat(boostExerciseStepsMet(200, 400, 800, 1500, 200, 400, 800, 1500)).isFalse()
    }

    @Test fun `daytime inactivity baseline is eligible but night and sleep gates stay closed`() {
        assertThat(StepFeed.inactivityEligible(
            stepsAvailable = true, currentProfileSwitch = 100,
            recentSteps60Min = 0, inactivitySteps = 400,
            sleepInActive = false, asleep = false, inNightWindow = false
        )).isTrue()
        assertThat(boostNightGateOpen(false, false)).isTrue()
        assertThat(boostNightGateOpen(true, false)).isFalse()
        assertThat(boostNightGateOpen(false, true)).isFalse()
    }
}
