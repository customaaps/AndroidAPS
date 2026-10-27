package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HrActivityDiagnosticsTest {
    private val now = 1_700_000_000_000L
    private val calc = HrActivityDiagnostics
    private val window = 15
    private fun reading(bpm: Double, minutesAgo: Long = 1L, duration: Long = 60_000L,
                        valid: Boolean = true) =
        HrActivityDiagnostics.Reading(now - minutesAgo * 60_000L, duration, bpm, valid)

    private fun result(
        bpm: Double, steps: Int?, stress: Boolean = false,
        count: Int = 3,
    ): HrActivityDiagnostics.Classification =
        calc.classify(
            List(count) { i -> reading(bpm, (i + 1).toLong()) },
            now, window, 180, 60, steps, stress
        )

    @Test fun emptyHistoryFallsBackToUnavailable() {
        val value = calc.classify(emptyList(), now, window, 180, 60, 200)
        assertEquals(HrActivityDiagnostics.Zone.NONE, value.zone)
        assertEquals(HrActivityDiagnostics.SignalStatus.MISSING_HR, value.status)
        assertEquals(HrActivityDiagnostics.ExerciseState.RESTING, value.state)
        assertNull(value.averageBpm)
    }

    @Test fun weightedAverageUsesDurationNotSampleCount() {
        val samples = listOf(reading(100.0, 1L, 30_000L), reading(50.0, 2L, 90_000L))
        assertEquals(62.5, calc.weightedAverage(samples, now, window))
    }

    @Test fun cutoffIsExclusiveAndCurrentInstantIsIncluded() {
        assertNull(calc.weightedAverage(listOf(reading(100.0, 15)), now, window))
        val atNow = HrActivityDiagnostics.Reading(now, 60_000L, 90.0)
        assertEquals(90.0, calc.weightedAverage(listOf(atNow), now, window))
    }

    @Test fun invalidAndFutureBpmRecordsAreNotUsed() {
        val samples = listOf(
            reading(80.0), reading(200.0, 2, valid = false),
            reading(Double.NaN, 3), reading(Double.POSITIVE_INFINITY, 4),
            reading(-10.0, 5), reading(120.0, 6, duration = 0),
            HrActivityDiagnostics.Reading(now + 10_000L, 60_000L, 125.0)
        )
        assertEquals(80.0, calc.weightedAverage(samples, now, window))
    }

    @Test fun noPositiveDurationProvidesNoHeartRate() {
        assertNull(calc.weightedAverage(listOf(reading(90.0, duration = 0)), now, window))
    }

    @Test fun frozenReadingsAreRejectedStartingAtFourthIdenticalSample() {
        val three = List(3) { i -> reading(124.0, (i + 1).toLong()) }
        val four = List(4) { i -> reading(124.0, (i + 1).toLong()) }
        assertFalse(calc.frozen(three, now, window))
        assertTrue(calc.frozen(four, now, window))
        val state = calc.classify(four, now, window, 180, 60, 5)
        assertEquals(HrActivityDiagnostics.SignalStatus.FROZEN_HR, state.status)
        assertEquals(HrActivityDiagnostics.Zone.NONE, state.zone)
    }

    @Test fun varyingReadingsAreNotFrozen() {
        val records = listOf(138.0, 141.0, 139.0, 142.0, 140.0)
            .mapIndexed { index, bpm -> reading(bpm, (index + 1).toLong()) }
        assertFalse(calc.frozen(records, now, window))
        assertEquals(
            HrActivityDiagnostics.ExerciseState.RESISTANCE,
            calc.classify(records, now, window, 180, 60, 10).state
        )
    }

    @Test fun exactZoneBoundariesMatchLegacyKarvonenClassifier() {
        assertEquals(HrActivityDiagnostics.Zone.VERY_LIGHT, calc.zoneFor(95.9, 180, 60))
        assertEquals(HrActivityDiagnostics.Zone.LIGHT, calc.zoneFor(96.0, 180, 60))
        assertEquals(HrActivityDiagnostics.Zone.MODERATE, calc.zoneFor(108.0, 180, 60))
        assertEquals(HrActivityDiagnostics.Zone.HARD, calc.zoneFor(132.0, 180, 60))
        assertEquals(HrActivityDiagnostics.Zone.MAXIMUM, calc.zoneFor(156.0, 180, 60))
    }

    @Test fun degenerateReserveUsesLegacyOneBpmFloor() {
        assertEquals(HrActivityDiagnostics.Zone.MAXIMUM, calc.zoneFor(61.0, 60, 60))
    }

    @Test fun nonFiniteAndInvalidProfilesCannotBeClassified() {
        assertEquals(HrActivityDiagnostics.Zone.NONE, calc.zoneFor(Double.NaN, 180, 60))
        assertEquals(HrActivityDiagnostics.Zone.NONE, calc.zoneFor(65.0, 0, 60))
        assertEquals(HrActivityDiagnostics.Zone.NONE, calc.zoneFor(65.0, 180, 0))
    }

    @Test fun highMovementAndHighHrIsVigorous() {
        val value = result(165.0, 400)
        assertEquals(HrActivityDiagnostics.ExerciseState.VIGOROUS_AEROBIC, value.state)
        assertEquals(HrActivityDiagnostics.Confidence.HIGH, value.confidence)
    }

    @Test fun moderateMovementAndHrIsModerateAerobic() {
        assertEquals(HrActivityDiagnostics.ExerciseState.MODERATE_AEROBIC, result(110.0, 200).state)
    }

    @Test fun lightMovementIsLightAerobic() {
        assertEquals(HrActivityDiagnostics.ExerciseState.LIGHT_AEROBIC, result(90.0, 50).state)
    }

    @Test fun elevatedHrAndLowMovementIsResistance() {
        assertEquals(HrActivityDiagnostics.ExerciseState.RESISTANCE, result(140.0, 10).state)
    }

    @Test fun legacyThirtyToNinetyNineStepDeadZoneIsResistanceNotResting() {
        assertEquals(HrActivityDiagnostics.ExerciseState.RESISTANCE, result(110.0, 64).state)
    }

    @Test fun optionalStressDetectionOnlyEnablesStressInZoneTwo() {
        assertEquals(HrActivityDiagnostics.ExerciseState.STRESS, result(100.0, 5, stress = true).state)
        assertEquals(HrActivityDiagnostics.ExerciseState.RESTING, result(100.0, 5, stress = false).state)
    }

    @Test fun lowHrAndKnownLowStepsMatchesLegacyInactiveDiagnostic() {
        assertEquals(HrActivityDiagnostics.ExerciseState.INACTIVE, result(65.0, 5).state)
    }

    @Test fun missingStepsNeverMeansSedentaryEvenWithNormalHr() {
        val unknown = result(65.0, null)
        assertEquals(HrActivityDiagnostics.ExerciseState.RESTING, unknown.state)
        assertEquals(HrActivityDiagnostics.SignalStatus.MISSING_STEPS, unknown.status)
        assertEquals(HrActivityDiagnostics.Zone.NONE, unknown.zone)
        val invalid = result(65.0, -1)
        assertEquals(HrActivityDiagnostics.SignalStatus.MISSING_STEPS, invalid.status)
    }

    @Test fun invalidWindowsFailClosed() {
        assertNull(calc.weightedAverage(listOf(reading(70.0)), now, 0))
        assertNull(calc.weightedAverage(listOf(reading(70.0)), now, 1500))
        assertNull(calc.weightedAverage(listOf(reading(70.0)), -1L, 15))
    }
}
