package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MealTimeDiagnosticsTest {
    private val diag = MealTimeDiagnostics
    private val day = 86_400_000L
    private val now = 100L * day + 86_000_000L

    private fun event(dayIndex: Long, minute: Int) = dayIndex * day + minute * 60_000L
    private fun analyse(
        timestamps: List<Long>,
        nightStart: Int = 22 * 60,
        nightEnd: Int = 7 * 60,
        offset: Long = 0L,
        current: Long = now,
    ) = diag.modes(timestamps, current, offset, nightStart, nightEnd)

    @Test fun emptyHistoryNeverCreatesMealMode() {
        assertTrue(analyse(emptyList()).isEmpty())
    }

    @Test fun fiveConfirmedMealsAreNotEnough() {
        assertTrue(analyse((90L..94L).map { event(it, 480) }).isEmpty())
    }

    @Test fun sixMealsOverOnlyThreeDistinctDaysAreNotEnough() {
        val events = (90L..92L).flatMap { listOf(event(it, 480), event(it, 485)) }
        assertTrue(analyse(events).isEmpty())
    }

    @Test fun sixConfirmedMealsOnSixDaysProduceBreakfastPattern() {
        val times = listOf(478, 482, 480, 479, 481, 480)
        val events = times.mapIndexed { index, minute ->
            event(90L + index.toLong(), minute)
        }
        val modes = analyse(events)
        assertEquals(1, modes.size)
        assertTrue(modes.single().centreMin in 478..482)
        assertEquals(6, modes.single().distinctDays)
        assertEquals(6, modes.single().eventCount)
    }

    @Test fun breakfastAndDinnerAreIndependentPatterns() {
        val breakfast = (90L..95L).map { event(it, 480) }
        val dinner = (90L..95L).map { event(it, 1140) }
        val modes = analyse(breakfast + dinner)
        assertEquals(2, modes.size)
        assertEquals(listOf(480, 1140), modes.map { it.centreMin }.sorted())
    }

    @Test fun midnightCrossingPatternUsesCircularTimeNotNoon() {
        val minutes = listOf(1430, 1435, 1438, 5, 8, 10)
        val events = minutes.mapIndexed { index, minute ->
            event(90L + index, minute)
        }
        val modes = analyse(events, nightStart = 0, nightEnd = 0)
        assertEquals(1, modes.size)
        assertTrue(modes.single().centreMin < 30 || modes.single().centreMin > 1410)
    }

    @Test fun overnightIntervalIncludesStartButNotEnd() {
        assertTrue(diag.inNightMinutes(1320, 1320, 420))
        assertTrue(diag.inNightMinutes(23 * 60 + 30, 1320, 420))
        assertTrue(diag.inNightMinutes(0, 1320, 420))
        assertTrue(diag.inNightMinutes(3 * 60, 1320, 420))
        assertFalse(diag.inNightMinutes(420, 1320, 420))
        assertFalse(diag.inNightMinutes(12 * 60, 1320, 420))
    }

    @Test fun equalNightStartAndEndRepresentEmptyExclusion() {
        assertFalse(diag.inNightMinutes(180, 360, 360))
        assertEquals(1, analyse((90L..95L).map { event(it, 180) }, 360, 360).size)
    }

    @Test fun daytimeNightWindowDoesNotWrap() {
        assertTrue(diag.inNightMinutes(60, 60, 360))
        assertTrue(diag.inNightMinutes(180, 60, 360))
        assertFalse(diag.inNightMinutes(360, 60, 360))
        assertFalse(diag.inNightMinutes(1380, 60, 360))
    }

    @Test fun nighttimeEventsAreFilteredBeforeLearningWhileDayModeSurvives() {
        val overnight = (90L..95L).map { event(it, 270) }
        val breakfast = (90L..95L).map { event(it, 480) }
        val unfiltered = analyse(overnight + breakfast, 0, 0)
        assertEquals(2, unfiltered.size)
        val filtered = analyse(overnight + breakfast)
        assertEquals(1, filtered.size)
        assertEquals(480, filtered.single().centreMin)
    }

    @Test fun overnightOnlyDoesNotProducePattern() {
        assertTrue(analyse((90L..95L).map { event(it, 270) }).isEmpty())
    }

    @Test fun timezoneOffsetChangesTheDetectedLocalClockTime() {
        val breakfastUtcSix = (90L..95L).map { event(it, 360) }
        val result = analyse(breakfastUtcSix, offset = 2L * 3_600_000L)
        assertEquals(480, result.single().centreMin)
    }

    @Test fun expiredAndFutureMealEventsCannotCreatePattern() {
        val old = (1L..6L).map { event(it, 480) }
        val future = (101L..106L).map { event(it, 480) }
        val recent = (90L..94L).map { event(it, 480) }
        assertTrue(analyse(old + future + recent).isEmpty())
    }

    @Test fun repeatedIdenticalTimestampIsNotSixSeparateMeals() {
        val single = event(90L, 480)
        assertTrue(analyse(List(6) { single }).isEmpty())
    }

    @Test fun invalidOffsetsClockAndNightConfigurationFailClosed() {
        val history = (90L..95L).map { event(it, 480) }
        assertTrue(analyse(history, offset = 19L * 3_600_000L).isEmpty())
        assertTrue(analyse(history, current = -1L).isEmpty())
        assertTrue(analyse(history, nightStart = -1).isEmpty())
        assertTrue(analyse(history, nightEnd = 1440).isEmpty())
    }

    @Test fun excessiveUntrustedInputCannotExhaustClustering() {
        val excessive = (0..500).map { event(90L, it % 1440) }
        assertTrue(analyse(excessive).isEmpty())
    }

    @Test fun mixedTrustedAndNoiseEventsOnlyYieldTrustedMode() {
        val breakfast = (90L..95L).map { event(it, 480) }
        val noise = listOf(event(90L, 750), event(91L, 920), event(92L, 1180))
        val result = analyse(noise + breakfast)
        assertEquals(1, result.size)
        assertEquals(6, result.single().eventCount)
    }
}
