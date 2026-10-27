package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoostHcStepSnapshotDiagnosticsTest {
    private val audit = BoostHcStepSnapshotDiagnostics
    private val day = 86_400_000L
    private val hour = 3_600_000L
    private val now = 100L * day + 12L * hour

    private fun step(
        source: String = "com.garmin.connect",
        dayIndex: Long = 99L,
        count: Long = 500L,
        startHour: Long = 9L,
        durationHours: Long = 1L,
        phoneDevice: Boolean = false,
    ) = BoostHcStepSnapshotDiagnostics.Record(
        source = source,
        startMs = dayIndex * day + startHour * hour,
        endMs = dayIndex * day + (startHour + durationHours) * hour,
        steps = count,
        phoneDevice = phoneDevice,
    )

    private fun build(
        records: List<BoostHcStepSnapshotDiagnostics.Record>,
        complete: Boolean = true,
        at: Long = now,
        offset: Long = 0L,
    ) = audit.build(records, complete, at, offset)

    @Test fun emptyHistoryIsNotAZeroStepMeasurement() {
        val result = build(emptyList())!!
        assertNull(result.chosenSource)
        assertTrue(result.perSourceDay.isEmpty())
        assertTrue(result.phoneDeviceDay.isEmpty())
        assertTrue(result.todayStepsBySource.isEmpty())
    }

    @Test fun incompletePaginatedHistoryMustBeRejected() {
        assertNull(build(emptyList(), complete = false))
        assertNull(build(listOf(step()), complete = false))
    }

    @Test fun independentWritersAreNotSummedTogether() {
        val result = build(listOf(step("garmin", count = 7000),
            step("fit", count = 3000)))!!
        assertEquals(7000L, result.perSourceDay["garmin"]!![99L])
        assertEquals(3000L, result.perSourceDay["fit"]!![99L])
        assertEquals(2, result.perSourceDay.size)
        assertEquals("garmin", result.chosenSource)
    }

    @Test fun coverageWinsOverLargestSingleDayTotal() {
        val result = build(listOf(
            step("steady", 97L, 1000L),
            step("steady", 98L, 1000L),
            step("steady", 99L, 1000L),
            step("sparse", 99L, 20000L),
        ))!!
        assertEquals("steady", result.chosenSource)
    }

    @Test fun zeroStepSourceDoesNotWinCoverage() {
        val result = build(listOf(
            step("zero", count = 0L),
            step("positive", count = 1000L),
        ))!!
        assertEquals("positive", result.chosenSource)
    }

    @Test fun intervalsFromSameSourceAndDayAreCombined() {
        val result = build(listOf(
            step("garmin", count = 300L, startHour = 9L),
            step("garmin", count = 400L, startHour = 10L),
        ))!!
        assertEquals(700L, result.perSourceDay["garmin"]!![99L])
    }

    @Test fun duplicatePageEntryDoesNotDoubleCountSteps() {
        val record = step(count = 500L)
        val result = build(listOf(record, record))!!
        assertEquals(1, result.duplicateRecordsSkipped)
        assertEquals(500L, result.perSourceDay[record.source]!![99L])
    }

    @Test fun phonePedometryIsCollectedAcrossWriterOrigins() {
        val result = build(listOf(
            step("fit", count = 600L, phoneDevice = true),
            step("garmin", count = 300L, phoneDevice = false),
            step("other", count = 400L, phoneDevice = true),
        ))!!
        assertEquals(1000L, result.phoneDeviceDay[99L])
        assertEquals(600L, result.perSourceDay["fit"]!![99L])
        assertEquals(300L, result.perSourceDay["garmin"]!![99L])
    }

    @Test fun localOffsetMovesRecordsIntoTheCorrectDay() {
        val atLateUtc = step("fit", dayIndex = 99L, count = 500L,
            startHour = 23L, durationHours = 1L)
        val result = build(listOf(atLateUtc), offset = 2L * hour)!!
        assertEquals(500L, result.perSourceDay["fit"]!![100L])
    }

    @Test fun crossMidnightIntervalsAreFlaggedAndAssignedToStartDay() {
        val crossing = step("fit", 99L, 800L, startHour = 23L,
            durationHours = 2L)
        val result = build(listOf(crossing))!!
        assertEquals(1, result.crossesLocalMidnight)
        assertEquals(800L, result.perSourceDay["fit"]!![99L])
    }

    @Test fun todaysPartialDayIsKeptSeparateFromCompletedDays() {
        val result = build(listOf(
            step("wear", 99L, 1200L),
            step("wear", 100L, 200L, startHour = 9L),
        ))!!
        assertEquals(200L, result.todayStepsBySource["wear"])
        assertEquals(1200L, result.perSourceDay["wear"]!![99L])
        assertEquals(100L, result.todayLocalDayIndex)
    }

    @Test fun malformedCountAndInvalidClockFailClosed() {
        assertNull(build(listOf(step(count = -1L))))
        assertNull(build(listOf(step(source = " "))))
        assertNull(build(emptyList(), at = 0L))
        assertNull(build(emptyList(), offset = 19L * hour))
        val reversed = step().copy(endMs = 99L * day)
        assertNull(build(listOf(reversed)))
    }

    @Test fun recordsOutsideHistoricalWindowAreRejected() {
        assertNull(build(listOf(step(dayIndex = 40L))))
        assertNull(build(listOf(step(dayIndex = 101L))))
    }

    @Test fun nextDayClockDoesNotConfuseYesterdayWithToday() {
        val old = build(listOf(step("wear", 99L, 2500L)))!!
        val later = build(listOf(step("wear", 99L, 2500L)),
            at = 101L * day + 12L * hour)!!
        assertEquals(0L, old.todayStepsBySource["wear"])
        assertEquals(0L, later.todayStepsBySource["wear"])
        assertEquals(100L, old.todayLocalDayIndex)
        assertEquals(101L, later.todayLocalDayIndex)
    }
}
