package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MealLeadWindowAuditTest {
    private val audit = MealLeadWindowAudit
    private val day = 86_400_000L
    private val minute = 60_000L

    private fun meal(atDay: Long, localMinute: Int) =
        atDay * day + localMinute * minute

    private fun breakfast() = MealEventHistoryAudit.History(
        (90L..95L).map { meal(it, 480) }
    )

    private fun inspect(
        minuteOfDay: Int,
        history: MealEventHistoryAudit.History = breakfast(),
        lead: Int = 60,
        nightStart: Int = 1320,
        nightEnd: Int = 420,
        offset: Long = 0L,
        dayIndex: Long = 100L,
    ) = audit.inspect(
        history = history,
        nowMs = meal(dayIndex, minuteOfDay),
        localOffsetMs = offset,
        nightStartMin = nightStart,
        nightEndMin = nightEnd,
        leadMaxMin = lead,
    )

    @Test fun sixConfirmedMealsProduceOneTrustedModeButNoAutomaticTarget() {
        val r = inspect(425)!!
        assertEquals(1, r.trustedModeCount)
        assertEquals(480, r.historicalMatch!!.centreMinute)
        assertEquals(6, r.historicalMatch.supportingEvents)
        assertEquals(6, r.historicalMatch.supportingDays)
        assertEquals(55, r.historicalMatch.minutesBeforeMeal)
    }

    @Test fun openingBoundaryIsInclusiveAtExactlySixtyMinutes() {
        val r = inspect(420)!!
        assertEquals(60, r.historicalMatch!!.minutesBeforeMeal)
        assertEquals(60, r.openBeforeMinutes)
        assertNull(inspect(419)!!.historicalMatch)
    }

    @Test fun closingBoundaryIsInclusiveAtFortyFiveMinutes() {
        assertEquals(45, inspect(435)!!.historicalMatch!!.minutesBeforeMeal)
        assertNull(inspect(436)!!.historicalMatch)
        assertNull(inspect(480)!!.historicalMatch)
    }

    @Test fun lowConfiguredLeadPreservesTenMinuteMinimumSpan() {
        val within = inspect(427, lead = 30)!!
        assertEquals(55, within.openBeforeMinutes)
        assertEquals(53, within.historicalMatch!!.minutesBeforeMeal)
        assertNull(inspect(420, lead = 30)!!.historicalMatch)
    }

    @Test fun longerUserLeadCanExtendTheHistoricalPreviewWindow() {
        val r = inspect(400, lead = 90)!!
        assertEquals(80, r.historicalMatch!!.minutesBeforeMeal)
        assertEquals(90, r.openBeforeMinutes)
    }

    @Test fun fewerThanSixConfirmedMealsCannotCreateWindow() {
        val h = MealEventHistoryAudit.History(
            (90L..94L).map { meal(it, 480) }
        )
        val r = inspect(425, history = h)!!
        assertEquals(0, r.trustedModeCount)
        assertNull(r.historicalMatch)
    }

    @Test fun repeatedOneDayMealsCannotBecomeTrustedMode() {
        val h = MealEventHistoryAudit.History(
            listOf(480, 481, 482, 483, 484, 485).map { meal(90, it) }
        )
        val r = inspect(425, history = h)!!
        assertEquals(0, r.trustedModeCount)
        assertNull(r.historicalMatch)
    }

    @Test fun configuredNightExclusionStopsOvernightPatternFromMatching() {
        val overnight = (90L..95L).map { meal(it, 270) }
        val h = MealEventHistoryAudit.History(overnight)
        val neutral = inspect(215, history = h)!!
        assertEquals(0, neutral.trustedModeCount)
        assertNull(neutral.historicalMatch)
        val unfiltered = inspect(215, history = h,
            nightStart = 0, nightEnd = 0)!!
        assertEquals(55, unfiltered.historicalMatch!!.minutesBeforeMeal)
    }

    @Test fun midnightCircularWindowDoesNotMistakeNextDayForNoon() {
        val midnightMeal = MealEventHistoryAudit.History(
            (90L..95L).map { meal(it, 15) }
        )
        val r = inspect(23 * 60 + 20, history = midnightMeal,
            nightStart = 0, nightEnd = 0)!!
        assertEquals(55, r.historicalMatch!!.minutesBeforeMeal)
    }

    @Test fun timezoneOffsetIsAppliedConsistentlyToModeAndCurrentTime() {
        val utcBreakfast = MealEventHistoryAudit.History(
            (90L..95L).map { meal(it, 360) }
        )
        val r = inspect(305, history = utcBreakfast,
            offset = 2L * 3_600_000L)!!
        assertEquals(425, r.currentLocalMinute)
        assertEquals(480, r.historicalMatch!!.centreMinute)
    }

    @Test fun invalidInputsAbstainInsteadOfOpeningHistoricalWindow() {
        val good = breakfast()
        assertNull(inspect(425, history = good, lead = -1))
        assertNull(inspect(425, history = good, lead = 1441))
        assertNull(inspect(425, history = good, nightStart = -1))
        assertNull(inspect(425, history = good, nightEnd = 1440))
        assertNull(inspect(425, history = good, offset = 19L * 3_600_000L))
        assertNull(audit.inspect(good, nowMs = 0L,
            localOffsetMs = 0, nightStartMin = 1320,
            nightEndMin = 420, leadMaxMin = 60))
    }

    @Test fun invalidOrDuplicatePersistedMealDataCannotBecomeEvidence() {
        val duplicate = MealEventHistoryAudit.History(
            listOf(meal(90, 480), meal(90, 480))
        )
        assertNull(inspect(425, history = duplicate))
        val reversed = MealEventHistoryAudit.History(
            listOf(meal(95, 480), meal(90, 480))
        )
        assertNull(inspect(425, history = reversed))
    }

    @Test fun noHistoryIsAnExplicitNoHitResult() {
        val r = inspect(425, history = MealEventHistoryAudit.History())!!
        assertEquals(0, r.trustedModeCount)
        assertNull(r.historicalMatch)
        assertTrue(r.openBeforeMinutes >= 55)
    }
}
