package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NightClockWindowDiagnosticsTest {
    private val clock = NightClockWindowDiagnostics
    private val midnight = 1_784_505_600_000L
    private val minute = 60_000L
    private fun at(h: Int, m: Int = 0): Long =
        midnight + (h * 60L + m) * minute
    private fun overnight(now: Long): Boolean? =
        clock.inspect(now, at(22), at(7))

    @Test fun wrappedNightAcceptsLateEveningAndEarlyMorning() {
        assertTrue(overnight(at(23))!!)
        assertTrue(overnight(at(2))!!)
        assertTrue(overnight(at(6, 9))!!)
    }

    @Test fun wrappedNightExcludesMiddayAndPreNightMinutes() {
        assertFalse(overnight(at(12))!!)
        assertFalse(overnight(at(21, 59))!!)
    }

    @Test fun startIsInclusiveAndEndIsExclusive() {
        assertTrue(overnight(at(22))!!)
        assertTrue(overnight(at(6, 59))!!)
        assertFalse(overnight(at(7))!!)
    }

    @Test fun equalTimesMeanEmptyNightNotWholeDay() {
        assertFalse(clock.inspect(at(3), at(7), at(7))!!)
        assertFalse(clock.inspect(at(7), at(7), at(7))!!)
        assertFalse(clock.inspect(at(15), at(7), at(7))!!)
    }

    @Test fun nonWrappingWindowDoesNotReadOutsideItsHours() {
        assertTrue(clock.inspect(at(3), at(1), at(5))!!)
        assertFalse(clock.inspect(at(0, 30), at(1), at(5))!!)
        assertFalse(clock.inspect(at(6), at(1), at(5))!!)
        assertFalse(clock.inspect(at(5), at(1), at(5))!!)
    }

    @Test fun scheduledClockResultNeedsNoNightModePolicyFlag() {
        val sourceClock = clock.inspectLocal(at(6, 9), midnight, 22 * 60, 7 * 60)
        assertEquals(true, sourceClock)
    }

    @Test fun timeCoordinatesCannotBeNegative() {
        assertNull(clock.inspect(-1L, at(22), at(7)))
        assertNull(clock.inspect(at(2), -1L, at(7)))
        assertNull(clock.inspect(at(2), at(22), -1L))
    }

    @Test fun malformedScheduleMinutesDoNotSilentlyBecomeAnAllDayNight() {
        assertNull(clock.inspectLocal(at(2), midnight, 1440, 420))
        assertNull(clock.inspectLocal(at(2), midnight, -1, 420))
        assertNull(clock.inspectLocal(at(2), midnight, 1320, 1440))
        assertNull(clock.inspectLocal(at(2), -1L, 1320, 420))
    }

    @Test fun reversedRangeSpanningMoreThanDayIsRejected() {
        assertNull(clock.inspect(midnight + 2 * 60 * minute,
            midnight + 2 * 86_400_000L, midnight))
    }

    @Test fun extremeFutureTimeBasisCannotOverflow() {
        assertNull(clock.inspectLocal(100L, Long.MAX_VALUE, 1320, 420))
    }
}
