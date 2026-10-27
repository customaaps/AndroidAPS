package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DailyStepActivityDiagnosticsTest {
    private val d = DailyStepActivityDiagnostics

    private fun history(vararg rows: Pair<Long, Int>) =
        DailyStepActivityDiagnostics.History(rows.associate { (day, count) ->
            day to DailyStepActivityDiagnostics.DailyTotal(day, count, "wear")
        })

    @Test fun coldStartCannotInventBaselineOrInactivity() {
        val h = history(*(1L..5L).map { it to 8000 }.toTypedArray())
        assertNull(d.baseline(h, 10))
        val result = d.shadow(h, 10)
        assertEquals("insufficient-history", result.reason)
        assertEquals(0.0, result.wouldDeltaIsfPct)
        assertNull(result.activityRatio)
    }

    @Test fun medianExcludesTheMostRecentTwoDays() {
        val h = history(*(1L..10L).map {
            it to if (it >= 9L) 20_000 else 8_000
        }.toTypedArray())
        assertEquals(8000, d.baseline(h, 11))
    }

    @Test fun exactlySevenQualifyingDaysAreEnough() {
        val h = history(*(1L..7L).map { it to 9000 }.toTypedArray())
        assertEquals(9000, d.baseline(h, 10))
    }

    @Test fun increasedCompletedDayLoadProducesPositiveShadowOnly() {
        val h = history(*(1L..11L).map { it to if (it == 11L) 16_000 else 8_000 }
            .toTypedArray())
        val r = d.shadow(h, 12)
        assertEquals("activity-load", r.reason)
        assertTrue(r.wouldDeltaIsfPct > 9.0 && r.wouldDeltaIsfPct < 11.0)
        assertEquals(8000, r.baselineSteps)
    }

    @Test fun activityShadowCapsAtFifteenPercent() {
        val h = history(*(1L..11L).map {
            it to if (it == 11L) 40_000 else 8_000
        }.toTypedArray())
        assertEquals(15.0, d.shadow(h, 12).wouldDeltaIsfPct)
    }

    @Test fun inactivityShadowHasSmallerEightPercentCeiling() {
        val h = history(*(1L..11L).map {
            it to if (it == 11L) 1_000 else 10_000
        }.toTypedArray())
        val r = d.shadow(h, 12)
        assertEquals("inactivity", r.reason)
        assertTrue(r.wouldDeltaIsfPct < 0.0)
        assertTrue(r.wouldDeltaIsfPct >= -8.0)
        assertTrue(d.INACTIVITY_MAX_ISF_PCT < d.ACTIVITY_MAX_ISF_PCT)
    }

    @Test fun unchangedActivityHasZeroShadowComparison() {
        val h = history(*(1L..11L).map { it to 9000 }.toTypedArray())
        assertEquals(0.0, d.shadow(h, 12).wouldDeltaIsfPct)
    }

    @Test fun lastDayIsRequiredEvenWithAValidBaseline() {
        val h = history(*(1L..10L).map { it to 8000 }.toTypedArray())
        assertEquals("insufficient-history", d.shadow(h, 12).reason)
    }

    @Test fun mergerNeverIncludesCurrentPartialDayOrAncientDays() {
        val original = history(72L to 100, 101L to 999)
        val updated = d.merge(original, listOf(
            DailyStepActivityDiagnostics.DailyTotal(75, 700, "wear"),
            DailyStepActivityDiagnostics.DailyTotal(102, 900, "wear"),
            DailyStepActivityDiagnostics.DailyTotal(40, 1000, "wear"),
        ), 102)!!
        assertTrue(75L in updated.days.keys)
        assertFalse(102L in updated.days.keys)
        assertFalse(72L in updated.days.keys)
        assertFalse(40L in updated.days.keys)
    }

    @Test fun previousCompletedTotalCanOnlyBeRevisedUpward() {
        val old = history(99L to 4142)
        val lowered = d.merge(old, listOf(
            DailyStepActivityDiagnostics.DailyTotal(99L, 739, "phone")
        ), 100)!!
        assertEquals(4142, lowered.days[99L]!!.steps)
        val raised = d.merge(lowered, listOf(
            DailyStepActivityDiagnostics.DailyTotal(99L, 4500, "wear")
        ), 100)!!
        assertEquals(4500, raised.days[99L]!!.steps)
        assertEquals(4142, old.days[99L]!!.steps)
    }

    @Test fun missingPreviousDayUsesBaselineRatherThanFabricatingZero() {
        val h = history(*(1L..9L).map { it to 8_000 }.toTypedArray(),
            11L to 16_000)
        val r = d.shadow(h, 12)
        assertEquals(13_333.333333333334 / 8000.0, r.activityRatio!!, 0.00001)
    }

    @Test fun sameDayPaceComparisonIsRaiseOnly() {
        assertEquals(0.0, d.intraday(2000, 14000, 15)!!.wouldDeltaIsfPct)
        val r = d.intraday(18000, 14000, 15)!!
        assertTrue(r.ratio!! > 1.5)
        assertTrue(r.wouldDeltaIsfPct > 10.0)
        assertEquals(9240, r.expectedByNow)
    }

    @Test fun overnightDiurnalFloorPreventsDividingByZero() {
        val r = d.intraday(500, 14000, 3)!!
        assertEquals(280, r.expectedByNow)
        assertTrue(r.wouldDeltaIsfPct >= 0.0)
    }

    @Test fun invalidCumulativeStepsCannotBeInterpretedAsInactivity() {
        assertNull(d.intraday(-1, 8000, 12))
        assertNull(d.intraday(1000, -1, 12))
        assertNull(d.intraday(1000, 8000, 24))
        assertNull(d.merge(history(), listOf(
            DailyStepActivityDiagnostics.DailyTotal(90, -1, "phone")
        ), 100))
    }

    @Test fun localEpochDayFollowsOffsetAndProtectsOverflow() {
        val utc = 86_400_000L + 30L * 60_000L
        assertEquals(1L, d.dayIndex(utc, 3_600_000L))
        assertEquals(0L, d.dayIndex(utc, -3_600_000L))
        assertEquals(-1L, d.dayIndex(0L, -3_600_000L))
        assertNull(d.dayIndex(Long.MAX_VALUE, 3_600_000L))
        assertNull(d.dayIndex(utc, 19L * 3_600_000L))
    }

    @Test fun noBaselineMeansNoIntradayShadow() {
        val r = d.intraday(20000, null, 14)!!
        assertNull(r.ratio)
        assertEquals(0.0, r.wouldDeltaIsfPct)
        assertEquals(0, r.expectedByNow)
    }
}
