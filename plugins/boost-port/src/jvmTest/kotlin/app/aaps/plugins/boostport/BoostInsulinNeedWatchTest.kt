package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoostV5.InsulinNeedWatch as Watch
import kotlin.test.*

class BoostInsulinNeedWatchTest {
    private val day = 86_400_000L
    private val start = 20_000L * day
    private val baseline = List(30) { 30.0 + it % 5 - 2 }
    private fun days(values: List<Double>, basal: (Int) -> Double? = { 16.0 }) =
        values.mapIndexed { i, value -> Watch.Day(start + i * day, value, basal(i)) }

    @Test fun risingAndFallingThreeDayWindowsCrossTheSourceBand() {
        assertEquals(Watch.Status.ABOVE, Watch.evaluate(days(baseline + listOf(40.0, 41.0, 42.0))).status)
        assertEquals(Watch.Status.BELOW, Watch.evaluate(days(baseline + listOf(22.0, 21.0, 23.0))).status)
        assertEquals(Watch.Status.WITHIN, Watch.evaluate(days(baseline + listOf(30.0, 29.0, 31.0))).status)
    }
    @Test fun aShortBaselineAndNearEmptyRecentDayProduceNoVerdict() {
        assertEquals(Watch.Status.FEW_DAYS, Watch.evaluate(days(List(15) { 30.0 })).status)
        assertEquals(Watch.Status.FEW_DAYS, Watch.evaluate(days(baseline + listOf(30.0, 2.0, 31.0))).status)
    }
    @Test fun oldGapIsExcludedWithoutHidingARise() {
        val values = baseline.toMutableList().also { it[10] = 1.5 } + listOf(40.0, 41.0, 42.0)
        val result = Watch.evaluate(days(values))
        assertEquals(29, result.baselineDays)
        assertEquals(Watch.Status.ABOVE, result.status)
    }
    @Test fun missingYesterdayDoesNotMoveTheRecentWindowBack() {
        val rows = days(baseline + listOf(40.0, 41.0, 42.0))
        assertEquals(Watch.Status.FEW_DAYS, Watch.evaluate(rows.dropLast(1), rows.last().startMs).status)
    }
    @Test fun profileChangeExplainsADoseChangeWhileMissingProfileDoesNotInventOne() {
        val values = baseline + listOf(16.0, 15.0, 15.5)
        assertEquals(Watch.Status.PROFILE_CHANGED, Watch.evaluate(days(values) { if (it < 30) 16.0 else 8.0 }).status)
        assertEquals(Watch.Status.BELOW, Watch.evaluate(days(values) { null }).status)
    }
    @Test fun temporaryProfilePercentageIsRemovedAndPermanentPercentageIsKept() {
        assertEquals(16.0, Watch.profileDailyBasal(11.2, 70, 7_200_000L), 1e-9)
        assertEquals(19.2, Watch.profileDailyBasal(19.2, 120, 0L), 1e-9)
    }
    @Test fun notificationCooldownIsFourteenDaysAndIndependentForEachDirection() {
        val above = Watch.evaluate(days(baseline + listOf(40.0, 41.0, 42.0)))
        val now = start + 40 * day
        assertTrue(Watch.shouldNotify(above, 0, now - day, now))
        assertFalse(Watch.shouldNotify(above, now - 13 * day, 0, now))
        assertTrue(Watch.shouldNotify(above, now - 14 * day, 0, now))
    }
    @Test fun tokensAreFiniteOrUseSourceNAForZeroSpread() {
        assertTrue(Watch.evaluate(days(baseline + listOf(40.0, 41.0, 42.0))).token().startsWith("ndw=ABOVE/z="))
        assertEquals("ndw=WITHIN/z=NA/p=+0/n=30", Watch.evaluate(days(List(33) { 30.0 })).token())
    }
}
