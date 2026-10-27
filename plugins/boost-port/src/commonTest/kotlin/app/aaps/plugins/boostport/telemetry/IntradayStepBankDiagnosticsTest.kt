package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntradayStepBankDiagnosticsTest {
    private val bank = IntradayStepBankDiagnostics
    private val today = 20_000L

    @Test fun watchesMidnightResetCannotEraseEarlierDailyPeak() {
        var state = IntradayStepBankDiagnostics.Bank()
        state = bank.advance(state, today, mapOf("wear" to 3_800, "phone" to 700))!!.bank
        state = bank.advance(state, today, mapOf("wear" to 4_142, "phone" to 739))!!.bank
        state = bank.advance(state, today, mapOf("wear" to 0, "phone" to 739))!!.bank
        val rollover = bank.advance(state, today + 1, mapOf("wear" to 12, "phone" to 3))!!
        assertEquals(4_142, rollover.closedDayTotals.single { it.source == "wear" }.steps)
        assertEquals(739, rollover.closedDayTotals.single { it.source == "phone" }.steps)
        assertEquals(mapOf("wear" to 12, "phone" to 3), rollover.bank.maximumBySource)
    }

    @Test fun laterLowerReadingNeverReplacesBankedMaximum() {
        val first = bank.advance(IntradayStepBankDiagnostics.Bank(), today,
            mapOf("wear" to 500))!!.bank
        val next = bank.advance(first, today, mapOf("wear" to 300))!!.bank
        assertEquals(500, next.maximumBySource["wear"])
        assertEquals(500, first.maximumBySource["wear"])
    }

    @Test fun coldSensorReadingAfterAppRestartStillRetainsPeakWhenBankPassedIn() {
        val previous = IntradayStepBankDiagnostics.Bank(today,
            mapOf("wear" to 4142, "phone" to 739))
        val resumed = bank.advance(previous, today,
            mapOf("wear" to 0, "phone" to 100))!!.bank
        val rollover = bank.advance(resumed, today + 1, emptyMap())!!
        assertEquals(4142, rollover.closedDayTotals.single { it.source == "wear" }.steps)
        assertEquals(739, rollover.closedDayTotals.single { it.source == "phone" }.steps)
    }

    @Test fun noZeroStepDayIsFabricatedAtRollover() {
        val previous = bank.advance(IntradayStepBankDiagnostics.Bank(), today,
            mapOf("phone" to 0, "wear" to 100))!!.bank
        val r = bank.advance(previous, today + 1, emptyMap())!!
        assertEquals(listOf("wear"), r.closedDayTotals.map { it.source })
    }

    @Test fun staleBankIsDiscardedAtRollover() {
        val previous = IntradayStepBankDiagnostics.Bank(today - bank.WINDOW_DAYS - 1,
            mapOf("wear" to 5000))
        val r = bank.advance(previous, today, mapOf("phone" to 4))!!
        assertTrue(r.closedDayTotals.isEmpty())
        assertEquals(mapOf("phone" to 4), r.bank.maximumBySource)
    }

    @Test fun futureBankDoesNotCloseIntoCurrentHistory() {
        val previous = IntradayStepBankDiagnostics.Bank(today + 2,
            mapOf("wear" to 5000))
        val r = bank.advance(previous, today, emptyMap())!!
        assertTrue(r.closedDayTotals.isEmpty())
        assertEquals(today, r.bank.localDayIndex)
        assertTrue(r.bank.maximumBySource.isEmpty())
    }

    @Test fun oldestValidCompletedDayIsIncludedAtWindowBoundary() {
        val previous = IntradayStepBankDiagnostics.Bank(today - bank.WINDOW_DAYS,
            mapOf("wear" to 800))
        val r = bank.advance(previous, today, emptyMap())!!
        assertEquals(1, r.closedDayTotals.size)
        assertEquals(today - bank.WINDOW_DAYS, r.closedDayTotals.single().dayIndex)
    }

    @Test fun garminPackageNameIsCanonicalizedForMerge() {
        val r = bank.advance(IntradayStepBankDiagnostics.Bank(), today,
            mapOf("com.garmin.android.apps.connectmobile" to 900))!!
        assertEquals(mapOf("garmin" to 900), r.bank.maximumBySource)
    }

    @Test fun twoAliasesOfSameSourceKeepHighestValue() {
        val r = bank.advance(IntradayStepBankDiagnostics.Bank(), today,
            mapOf("garmin" to 900,
                "com.garmin.android.apps.connectmobile" to 1_100))!!
        assertEquals(mapOf("garmin" to 1_100), r.bank.maximumBySource)
    }

    @Test fun blankUntrustedAndNegativeStepsFailClosed() {
        assertNull(bank.advance(IntradayStepBankDiagnostics.Bank(), today,
            mapOf(" " to 120)))
        assertNull(bank.advance(IntradayStepBankDiagnostics.Bank(), today,
            mapOf("wear" to -2)))
        assertNull(bank.advance(IntradayStepBankDiagnostics.Bank(), -1, emptyMap()))
    }

    @Test fun corruptStoredBankNeverReentersValidHistory() {
        assertNull(bank.advance(
            IntradayStepBankDiagnostics.Bank(today, mapOf("wear" to -1)),
            today, emptyMap()))
        assertNull(bank.advance(
            IntradayStepBankDiagnostics.Bank(-2, emptyMap()),
            today, emptyMap()))
    }

    @Test fun newDayCloseEmitsOnlyOneDayAndDoesNotMutateOldBank() {
        val previous = IntradayStepBankDiagnostics.Bank(today, mapOf("wear" to 500))
        val first = bank.advance(previous, today + 1, mapOf("wear" to 2))!!
        val second = bank.advance(first.bank, today + 1, mapOf("wear" to 3))!!
        assertEquals(1, first.closedDayTotals.size)
        assertTrue(second.closedDayTotals.isEmpty())
        assertEquals(500, previous.maximumBySource["wear"])
        assertEquals(3, second.bank.maximumBySource["wear"])
        assertFalse(first.bank == second.bank)
    }
}
