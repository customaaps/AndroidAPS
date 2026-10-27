package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoostVwaTddShadowMathTest {
    private val vwa = BoostVwaTddShadowMath
    private val day = 86_400_000L
    private val hour = 3_600_000L

    private fun populationCurve() =
        (0 until vwa.BUCKETS).map { (it + 1).toDouble() / vwa.BUCKETS }

    @Test fun sufficientFractionProjectsDayAndBlendsHalfWithSevenDayTdd() {
        val r = vwa.project(0.25, deliveredToday = 10.0, tdd7D = 30.0)!!
        assertEquals(40.0, r.unboundedProjection)
        assertEquals(40.0, r.boundedProjection)
        assertEquals(35.0, r.comparisonBlend)
        assertEquals(false, r.usedPreviousDay)
    }

    @Test fun fractionBelowTenthFallsBackToCompletedPreviousDay() {
        val r = vwa.project(0.05, deliveredToday = 1.0, tdd7D = 30.0,
            previousDayTotal = 45.0)!!
        assertEquals(45.0, r.unboundedProjection)
        assertEquals(37.5, r.comparisonBlend)
        assertEquals(true, r.usedPreviousDay)
    }

    @Test fun absentPreviousDayFallsBackToSevenDayAverage() {
        val r = vwa.project(0.02, deliveredToday = 1.0, tdd7D = 30.0)!!
        assertEquals(30.0, r.unboundedProjection)
        assertEquals(30.0, r.comparisonBlend)
    }

    @Test fun floorIsInclusiveAtExactTenth() {
        val r = vwa.project(0.10, deliveredToday = 4.0, tdd7D = 30.0)!!
        assertEquals(false, r.usedPreviousDay)
        assertEquals(40.0, r.unboundedProjection)
    }

    @Test fun upperAndLowerSanityLimitsAreApplied() {
        val high = vwa.project(0.2, 50.0, 30.0)!!
        assertEquals(250.0, high.unboundedProjection)
        assertEquals(60.0, high.boundedProjection)
        assertEquals(45.0, high.comparisonBlend)
        val low = vwa.project(0.5, 1.0, 30.0)!!
        assertEquals(2.0, low.unboundedProjection)
        assertEquals(15.0, low.boundedProjection)
        assertEquals(22.5, low.comparisonBlend)
    }

    @Test fun malformedOrMissingInsulinHistoryIsNotProjected() {
        assertNull(vwa.project(Double.NaN, 2.0, 30.0))
        assertNull(vwa.project(-0.01, 2.0, 30.0))
        assertNull(vwa.project(1.01, 2.0, 30.0))
        assertNull(vwa.project(0.5, -1.0, 30.0))
        assertNull(vwa.project(0.5, null, 30.0))
        assertNull(vwa.project(0.5, 2.0, Double.POSITIVE_INFINITY))
        assertNull(vwa.project(0.5, 2.0, 0.0))
        assertNull(vwa.project(0.5, 2.0, 30.0, Double.NaN))
    }

    @Test fun anchorUsesQuietHourAndHandlesPreviousDay() {
        assertEquals(3 * hour, vwa.dayAnchorMs(4 * hour, anchorHour = 3))
        assertEquals(-21 * hour, vwa.dayAnchorMs(hour, anchorHour = 3))
        assertEquals(day + 3 * hour, vwa.dayAnchorMs(day + 4 * hour, anchorHour = 3))
    }

    @Test fun dayBucketUsesFiveMinuteGrid() {
        val anchor = 3 * hour
        assertEquals(0, vwa.bucket(anchor, anchor))
        assertEquals(0, vwa.bucket(anchor + 299_999L, anchor))
        assertEquals(1, vwa.bucket(anchor + 300_000L, anchor))
        assertEquals(287, vwa.bucket(anchor + day - 1L, anchor))
        assertNull(vwa.bucket(anchor + day, anchor))
        assertNull(vwa.bucket(anchor - 1L, anchor))
    }

    @Test fun invalidAnchorHourIsRejected() {
        assertNull(vwa.dayAnchorMs(0L, -1))
        assertNull(vwa.dayAnchorMs(0L, 24))
        assertNull(vwa.dayAnchorMs(-1L))
    }

    @Test fun sparseIncompleteCurveCannotBeLearned() {
        assertNull(vwa.foldCompletedDay(listOf(0.0), List(vwa.BUCKETS) { 2.0 }, 0))
        assertNull(vwa.foldCompletedDay(populationCurve(), listOf(0.0), 0))
        assertNull(vwa.foldCompletedDay(populationCurve(), List(vwa.BUCKETS) { 0.5 }, 0))
    }

    @Test fun dayIsNormalizedUsingMaximumObservedCumulativeTotal() {
        val records = List(vwa.BUCKETS) { idx ->
            when {
                idx < 150 -> 0.0
                idx == 150 -> 30.0
                idx > 150 -> 20.0
                else -> 0.0
            }
        }
        val fold = vwa.foldCompletedDay(populationCurve(), records, 0)!!
        assertEquals(30.0, fold.completedDayTotal)
        assertEquals(1, fold.curveDays)
        assertEquals(1.0, fold.curve.last(), 1e-9)
        assertTrue(fold.curve.zipWithNext().all { it.second >= it.first })
    }

    @Test fun curveFoldingDoesNotModifyTheSuppliedCurveOrRecords() {
        val base = populationCurve()
        val observed = List(vwa.BUCKETS) { it * 0.1 }
        val baseCopy = base.toList()
        val recordCopy = observed.toList()
        assertTrue(vwa.foldCompletedDay(base, observed, 3) != null)
        assertEquals(baseCopy, base)
        assertEquals(recordCopy, observed)
    }

    @Test fun curveFoldingRejectsMalformedHistoryWithoutDosingGuess() {
        val base = populationCurve()
        val observed = List(vwa.BUCKETS) { 1.0 }
        assertNull(vwa.foldCompletedDay(base.reversed(), observed, 1))
        assertNull(vwa.foldCompletedDay(base, observed, -1))
        assertNull(vwa.foldCompletedDay(base, observed, Int.MAX_VALUE))
        assertNull(vwa.foldCompletedDay(base, observed.toMutableList().apply {
            this[10] = Double.NaN
        }, 1))
    }
}
