package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StepHistoryBridgeDiagnosticsTest {
    private val bridge = StepHistoryBridgeDiagnostics

    private fun history(source: String, range: LongRange, steps: Int) =
        DailyStepActivityDiagnostics.History(
            range.associateWith { day ->
                DailyStepActivityDiagnostics.DailyTotal(day, steps, source)
            }
        )

    private fun history(source: String, counts: Map<Long, Int>) =
        DailyStepActivityDiagnostics.History(counts.mapValues { (day, steps) ->
            DailyStepActivityDiagnostics.DailyTotal(day, steps, source)
        })

    private fun multi(vararg sources: Pair<String, DailyStepActivityDiagnostics.History>) =
        StepHistoryBridgeDiagnostics.MultiHistory(sources.toMap())

    @Test fun medianCalibrationRequiresThreeNonzeroOverlappingDays() {
        val phone = history("phone", 1L..5L, 9000)
        val wear = history("wear", 1L..5L, 14000)
        assertEquals(9000.0 / 14000.0, bridge.calibration(phone, wear)!!, 1e-12)
        assertNull(bridge.calibration(history("phone", 1L..2L, 9000), wear))
        assertEquals(9000.0 / 14000.0, bridge.calibration(phone,
            history("wear", mapOf(1L to 0, 2L to 14000, 3L to 14000,
                4L to 14000, 5L to 14000)))!!, 1e-12)
    }

    @Test fun swappingWatchesUsesPhoneAsCommonCalibrationReference() {
        val source = multi(
            "phone" to history("phone", 0L..19L, 7000),
            "garmin" to history("garmin", 0L..9L, 9000),
            "wear" to history("wear", 10L..19L, 14000),
        )
        val result = bridge.phoneAnchored(source, 20)!!
        assertTrue(result.calibrated)
        assertEquals(20, result.history.days.size)
        assertEquals(7000, result.history.days[5L]!!.steps)
        assertEquals(7000, result.history.days[15L]!!.steps)
        assertEquals("garmin", result.history.days[5L]!!.source)
        assertEquals("wear", result.history.days[15L]!!.source)
        assertEquals(listOf("garmin", "wear"), result.donorsUsed)
        assertEquals(7000, DailyStepActivityDiagnostics.baseline(result.history, 20))
    }

    @Test fun wearerWinsATieWhenItsScaledCountMatchesPhone() {
        val source = multi(
            "phone" to history("phone", 0L..9L, 6000),
            "wear" to history("wear", 0L..9L, 12000),
        )
        val result = bridge.phoneAnchored(source, 10)!!
        assertEquals(6000, result.history.days[4L]!!.steps)
        assertEquals("wear", result.history.days[4L]!!.source)
    }

    @Test fun partialPhoneWarmupCannotEraseHigherWornCount() {
        val source = multi(
            "phone" to history("phone", mapOf(8L to 7000, 9L to 7000)),
            "wear" to history("wear", 0L..9L, 14000),
        )
        val result = bridge.phoneAnchored(source, 10)!!
        assertFalse(result.calibrated)
        assertEquals(14000, result.history.days[8L]!!.steps)
        assertEquals("wear", result.history.days[8L]!!.source)
    }

    @Test fun legacyRolloverUndercountShowsHeldBreadcrumb() {
        val source = multi(
            "phone" to history("phone", mapOf(8L to 3000, 9L to 3095)),
            "wear" to history("wear", mapOf(9L to 6224)),
        )
        val result = bridge.phoneAnchored(source, 10)!!
        assertEquals(6224, result.history.days[9L]!!.steps)
        assertEquals("held wear 6224 over phone 3095", result.heldNote)
    }

    @Test fun singleSourceDayHasNoHeldBreadcrumb() {
        val source = multi(
            "wear" to history("wear", mapOf(9L to 6224)),
        )
        val result = bridge.phoneAnchored(source, 10)!!
        assertNull(result.heldNote)
        assertFalse(result.calibrated)
    }

    @Test fun phoneUnitsAreScaledOnlyWithSufficientCalibration() {
        val phone = history("phone", 0L..9L, 7000)
        val wear = history("wear", 0L..9L, 14000)
        val source = multi("phone" to phone, "wear" to wear)
        assertEquals(5000, bridge.toPhoneUnits(10000, "wear", source))
        assertEquals(7000, bridge.toPhoneUnits(7000, "phone", source))
        assertEquals(10000, bridge.toPhoneUnits(10000, null, source))
        val uncalibrated = multi("phone" to history("phone", 0L..1L, 7000),
            "wear" to wear)
        assertEquals(10000, bridge.toPhoneUnits(10000, "wear", uncalibrated))
    }

    @Test fun sourceMergeOnlyKeepsCompletedDaysAndHigherCounts() {
        val initial = StepHistoryBridgeDiagnostics.MultiHistory()
        val first = bridge.mergeSource(initial, "wear", listOf(
            DailyStepActivityDiagnostics.DailyTotal(99L, 5000, "wear"),
            DailyStepActivityDiagnostics.DailyTotal(100L, 6000, "wear"),
        ), 100)!!
        val second = bridge.mergeSource(first, "wear", listOf(
            DailyStepActivityDiagnostics.DailyTotal(99L, 1500, "wear"),
        ), 100)!!
        assertEquals(5000, second.histories["wear"]!!.days[99L]!!.steps)
        assertFalse(100L in second.histories["wear"]!!.days)
    }

    @Test fun historyMergeCanonicalizesGarminPackageAndPrunesNoData() {
        val result = bridge.mergeSource(StepHistoryBridgeDiagnostics.MultiHistory(),
            "com.garmin.android.apps.connectmobile", listOf(
                DailyStepActivityDiagnostics.DailyTotal(99L, 9000,
                    "com.garmin.android.apps.connectmobile")
            ), 100)!!
        assertTrue("garmin" in result.histories)
        val empty = bridge.mergeSource(result, "garmin", emptyList(), 200)!!
        assertFalse("garmin" in empty.histories)
    }

    @Test fun corruptOrNegativeSourceHistoryIsRejected() {
        val broken = multi("wear" to history("wear", mapOf(99L to -1)))
        assertNull(bridge.phoneAnchored(broken, 100))
        assertNull(bridge.toPhoneUnits(100, "wear", broken))
        assertNull(bridge.mergeSource(broken, "phone", emptyList(), 100))
        assertNull(bridge.phoneAnchored(multi(), -1))
        assertNull(bridge.toPhoneUnits(-1, "phone", multi()))
    }

    @Test fun uncalibratedUnknownSourceNeedsExplicitTrustEvidence() {
        assertNull(bridge.mergeSource(StepHistoryBridgeDiagnostics.MultiHistory(),
            "unknown", emptyList(), 100))
        assertNull(bridge.toPhoneUnits(50, "unknown", multi()))
        assertEquals(0, bridge.phoneAnchored(multi(), 100)!!.history.days.size)
    }

    @Test fun noOutputDayCanBeTodaysIncompleteCount() {
        val source = multi("phone" to history("phone", 98L..101L, 4000))
        val result = bridge.phoneAnchored(source, 100)!!
        assertEquals(setOf(98L, 99L), result.history.days.keys)
        assertFalse(100L in result.history.days)
        assertFalse(101L in result.history.days)
    }
}
