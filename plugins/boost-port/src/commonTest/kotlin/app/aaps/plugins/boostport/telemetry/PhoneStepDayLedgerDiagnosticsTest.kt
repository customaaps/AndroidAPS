package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneStepDayLedgerDiagnosticsTest {
    private val d = PhoneStepDayLedgerDiagnostics
    private val initial = PhoneStepDayLedgerDiagnostics.State()

    @Test fun firstCounterReadingIsBaselineNotHistoricalDailySteps() {
        val first = d.observeRaw(initial, 100L, 4000L)!!
        assertEquals(PhoneStepDayLedgerDiagnostics.Event.DAY_STARTED, first.event)
        assertEquals(0L, first.reportedStepsToday)
        assertTrue(first.feedKnown)
        assertEquals(4000L, first.state.rawCounter)
        assertNull(d.observeRaw(initial, 100L, null)!!.reportedStepsToday)
    }

    @Test fun increasesAccumulateOnlyNewStepsNotBootTotal() {
        val first = d.observeRaw(initial, 100L, 4000L)!!
        val second = d.observeRaw(first.state, 100L, 4110L)!!
        assertEquals(110L, second.reportedStepsToday)
        assertEquals(PhoneStepDayLedgerDiagnostics.Event.RAW_INCREMENT, second.event)
        assertEquals(0L, first.reportedStepsToday)
    }

    @Test fun rebootCounterResetCountsNewSinceRebootSteps() {
        val first = d.observeRaw(initial, 100L, 4000L)!!
        val second = d.observeRaw(first.state, 100L, 4110L)!!
        val reset = d.observeRaw(second.state, 100L, 15L)!!
        assertEquals(125L, reset.reportedStepsToday)
        assertEquals(PhoneStepDayLedgerDiagnostics.Event.SENSOR_RESET, reset.event)
        val next = d.observeRaw(reset.state, 100L, 50L)!!
        assertEquals(160L, next.reportedStepsToday)
    }

    @Test fun missingCallbackNeverErasesKnownMovement() {
        val first = d.observeRaw(initial, 100L, 4000L)!!
        val observed = d.observeRaw(first.state, 100L, 4100L)!!
        val missing = d.observeRaw(observed.state, 100L, null)!!
        assertEquals(100L, missing.reportedStepsToday)
        assertEquals(PhoneStepDayLedgerDiagnostics.Event.NO_READING, missing.event)
        assertTrue(missing.feedKnown)
    }

    @Test fun localMidnightRolloverStartsNewDayWithoutYesterdayCounts() {
        var state = d.observeRaw(initial, 100L, 4000L)!!.state
        state = d.observeRaw(state, 100L, 4700L)!!.state
        val rollover = d.observeRaw(state, 101L, 4800L)!!
        assertEquals(0L, rollover.reportedStepsToday)
        assertEquals(101L, rollover.state.localDayIndex)
        val later = d.observeRaw(rollover.state, 101L, 4840L)!!
        assertEquals(40L, later.reportedStepsToday)
    }

    @Test fun healthConnectLateTodayTotalRaisesOnlyMaximum() {
        var state = d.observeRaw(initial, 100L, 4000L)!!.state
        state = d.observeRaw(state, 100L, 4700L)!!.state
        val higher = d.seedHealthConnect(state, 100L, 900L, 4700L)!!
        assertEquals(900L, higher.reportedStepsToday)
        assertEquals(PhoneStepDayLedgerDiagnostics.Event.HC_SEED, higher.event)
        val lower = d.seedHealthConnect(higher.state, 100L, 600L, 4700L)!!
        assertEquals(900L, lower.reportedStepsToday)
        assertEquals(PhoneStepDayLedgerDiagnostics.Event.HC_NO_CHANGE, lower.event)
    }

    @Test fun hcSeedingWithoutSensorStillShowsUnknownSensorFeed() {
        val result = d.seedHealthConnect(initial, 100L, 2000L, null)!!
        assertEquals(2000L, result.reportedStepsToday)
        assertFalse(result.feedKnown)
        assertNull(result.state.rawCounter)
        val baseline = d.observeRaw(result.state, 100L, 5000L)!!
        assertEquals(2000L, baseline.reportedStepsToday)
        assertTrue(baseline.feedKnown)
    }

    @Test fun missingHcDoesNotFabricateAZeroMovementMeasurement() {
        val none = d.seedHealthConnect(initial, 100L, null, null)!!
        assertNull(none.reportedStepsToday)
        assertFalse(none.feedKnown)
        val noneAgain = d.seedHealthConnect(none.state, 100L, null, null)!!
        assertNull(noneAgain.reportedStepsToday)
    }

    @Test fun midnightHcSeedCannotCarryYesterdayMaximum() {
        var state = d.seedHealthConnect(initial, 100L, 15000L, 6000L)!!.state
        val next = d.seedHealthConnect(state, 101L, 100L, 6050L)!!
        assertEquals(100L, next.reportedStepsToday)
        assertEquals(101L, next.state.localDayIndex)
        assertEquals(6050L, next.state.rawCounter)
    }

    @Test fun rawCounterIncrementCannotReduceHcReconstructedCount() {
        val seed = d.seedHealthConnect(initial, 100L, 1500L, 5000L)!!
        val next = d.observeRaw(seed.state, 100L, 5010L)!!
        assertEquals(1510L, next.reportedStepsToday)
    }

    @Test fun overflowSaturatesRatherThanWrappingNegative() {
        val state = PhoneStepDayLedgerDiagnostics.State(
            localDayIndex = 100L, rawCounter = 500L,
            stepsToday = Long.MAX_VALUE - 10L,
            hasDailyEvidence = true, sensorEverReported = true,
        )
        val next = d.observeRaw(state, 100L, 600L)!!
        assertEquals(Long.MAX_VALUE, next.reportedStepsToday)
    }

    @Test fun reversedDaysAndCorruptedSensorCountersFailClosed() {
        val state = d.observeRaw(initial, 100L, 4000L)!!.state
        assertNull(d.observeRaw(state, 99L, 5000L))
        assertNull(d.observeRaw(state, 100L, -1L))
        assertNull(d.seedHealthConnect(state, 100L, -1L, null))
        assertNull(d.seedHealthConnect(state, 99L, 1L, null))
        assertNull(d.observeRaw(initial, -1L, null))
        assertNull(d.observeRaw(initial.copy(stepsToday = -1L), 100L, 1L))
    }

    @Test fun returnValuesAreImmutableAndDoNotMutateOriginalLedger() {
        val first = d.observeRaw(initial, 100L, 4000L)!!
        val second = d.observeRaw(first.state, 100L, 4200L)!!
        assertEquals(0L, first.state.stepsToday)
        assertEquals(200L, second.state.stepsToday)
        assertEquals(-1L, initial.localDayIndex)
    }
}
