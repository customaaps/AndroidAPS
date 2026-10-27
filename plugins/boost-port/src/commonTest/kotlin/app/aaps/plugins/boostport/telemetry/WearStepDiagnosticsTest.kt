package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WearStepDiagnosticsTest {
    private val dayMs = 86_400_000L
    private val baseDay = 100L * dayMs
    private fun sample(offset: Long, count: Int) =
        WearStepDiagnostics.Reading(baseDay + offset, count)

    @Test fun repeatedWatchSamplesInOneFiveMinuteSlotUseMaximum() {
        val records = listOf(sample(60_000L, 120), sample(200_000L, 140),
            sample(360_000L, 200), sample(660_000L, 90))
        assertEquals(430, WearStepDiagnostics.stepsToday(records, baseDay, baseDay + 700_000L))
    }

    @Test fun sampleBeforeLocalDayOrAfterNowDoesNotCount() {
        val records = listOf(sample(-600_000L, 999), sample(120_000L, 80),
            sample(900_000L, 700))
        assertEquals(80, WearStepDiagnostics.stepsToday(records, baseDay, baseDay + 300_000L))
    }

    @Test fun freshAndResolveGraceHaveIndependentBoundaries() {
        val now = baseDay
        val exactlyTwelve = listOf(sample(-WearStepDiagnostics.FRESH_MS, 5))
        assertTrue(WearStepDiagnostics.isFresh(exactlyTwelve, now))
        val twentyMinutes = listOf(sample(-20 * 60_000L, 5))
        assertFalse(WearStepDiagnostics.isFresh(twentyMinutes, now))
        assertTrue(WearStepDiagnostics.isRecentlyFresh(twentyMinutes, now))
        assertFalse(WearStepDiagnostics.isRecentlyFresh(listOf(sample(-31 * 60_000L, 5)), now))
    }

    @Test fun absentReadingsAreNotReportedAsFresh() {
        assertFalse(WearStepDiagnostics.isFresh(emptyList(), baseDay))
        assertFalse(WearStepDiagnostics.isRecentlyFresh(emptyList(), baseDay))
        assertEquals(0, WearStepDiagnostics.stepsToday(emptyList(), baseDay, baseDay + 1000L))
    }

    @Test fun futureDatedReadingsNeverEstablishFreshness() {
        assertFalse(WearStepDiagnostics.isFresh(listOf(sample(1L, 2)), baseDay))
        assertFalse(WearStepDiagnostics.isRecentlyFresh(listOf(sample(1L, 2)), baseDay))
        assertEquals(0, WearStepDiagnostics.stepsToday(listOf(sample(1L, 2)), baseDay, baseDay))
    }

    @Test fun negativeStepWindowsAreIgnored() {
        val result = WearStepDiagnostics.stepsToday(
            listOf(sample(20_000L, -1), sample(40_000L, 17)), baseDay, baseDay + 100_000L
        )
        assertEquals(17, result)
    }

    @Test fun completedDaysExcludePartialCurrentDayAndDeduplicateSlots() {
        val records = listOf(
            sample(-dayMs + 60_000L, 12),
            sample(-dayMs + 180_000L, 18),
            sample(-dayMs + 400_000L, 25),
            sample(60_000L, 900)
        )
        val totals = WearStepDiagnostics.completedDailyTotals(records, 100L, 0L)
        assertEquals(listOf(WearStepDiagnostics.DailyTotal(99L, 43)), totals)
    }

    @Test fun localOffsetChangesWhichDayAReadingBelongsTo() {
        val records = listOf(sample(-60_000L, 11), sample(120_000L, 22))
        val totals = WearStepDiagnostics.completedDailyTotals(
            records, todayIndex = 101L, localOffsetMs = 2 * 60 * 60_000L
        )
        assertEquals(listOf(WearStepDiagnostics.DailyTotal(100L, 33)), totals)
    }

    @Test fun invalidTimeWindowIsEmpty() {
        assertEquals(0, WearStepDiagnostics.stepsToday(listOf(sample(20L, 2)), baseDay + 50L, baseDay))
    }

    @Test fun extremeTotalsSaturateWithoutWrappingToNegative() {
        val records = listOf(sample(100L, Int.MAX_VALUE), sample(400_000L, 50))
        assertEquals(Int.MAX_VALUE, WearStepDiagnostics.stepsToday(records, baseDay, baseDay + 450_000L))
    }
}
