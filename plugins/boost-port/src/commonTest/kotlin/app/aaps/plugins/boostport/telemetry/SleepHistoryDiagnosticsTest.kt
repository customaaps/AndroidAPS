package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepHistoryDiagnosticsTest {
    private val d = SleepHistoryDiagnostics
    private val day = 86_400_000L
    private val hour = 3_600_000L
    private val now = 48L * day

    private fun session(dayIndex: Long, reason: String? = "hr_steps",
                        onsetHour: Long = 22L, wakeHour: Long = 6L) =
        SleepHistoryDiagnostics.Session(
            sleepStartMs = dayIndex * day + onsetHour * hour,
            wakeMs = (dayIndex + 1L) * day + wakeHour * hour,
            wakeReason = reason,
        )

    @Test fun noHistoryYieldsUnknownRatherThanInventedSleep() {
        val s = d.summarize(emptyList(), now, 0L)
        assertEquals(0, s.validSessions)
        assertNull(s.onsetMinute)
        assertNull(s.wakeMinute)
        assertNull(s.meanDurationMinutes)
        assertNull(s.restingHrBpm)
    }

    @Test fun sixSessionsCannotEstablishLearnedSchedule() {
        val s = d.summarize((40L..45L).map { session(it) }, now, 0L)
        assertEquals(6, s.validSessions)
        assertNull(s.onsetMinute)
        assertNull(s.wakeMinute)
    }

    @Test fun sevenGenuineWakesProvideReadOnlyOnsetWakeAndDuration() {
        val s = d.summarize((40L..46L).map { session(it) }, now, 0L)
        assertEquals(7, s.validSessions)
        assertEquals(7, s.genuineWakes)
        assertEquals(22 * 60, s.onsetMinute)
        assertEquals(6 * 60, s.wakeMinute)
        assertEquals(8 * 60, s.meanDurationMinutes)
    }

    @Test fun boundaryWakeDoesNotTrainWakeTimeEvenWithManySessions() {
        val s = d.summarize((40L..46L).map { session(it, "boundary") }, now, 0L)
        assertEquals(7, s.validSessions)
        assertEquals(0, s.genuineWakes)
        assertEquals(22 * 60, s.onsetMinute)
        assertNull(s.wakeMinute)
    }

    @Test fun legacyMissingWakeReasonsAlsoCannotTrainWakeTime() {
        val s = d.summarize((40L..46L).map { session(it, null) }, now, 0L)
        assertEquals(0, s.genuineWakes)
        assertNull(s.wakeMinute)
    }

    @Test fun mixedBoundaryAndConfirmedEventsRequireSevenConfirmed() {
        val sessions = (40L..43L).map { session(it, "resume") } +
            (44L..46L).map { session(it, "boundary") }
        val s = d.summarize(sessions, now, 0L)
        assertEquals(7, s.validSessions)
        assertEquals(4, s.genuineWakes)
        assertNull(s.wakeMinute)
    }

    @Test fun stepsOnlyWakeIsGenuineAlongsideHrAndResume() {
        val events = listOf("steps", "resume", "hr_steps", "steps", "resume", "hr_steps", "steps")
        val s = d.summarize((40L..46L).mapIndexed { i, index -> session(index, events[i]) }, now, 0L)
        assertEquals(7, s.genuineWakes)
        assertEquals(6 * 60, s.wakeMinute)
    }

    @Test fun futureBackwardTooLongAndOutsideWindowSessionsAreIgnored() {
        val good = session(40L)
        val reversed = SleepHistoryDiagnostics.Session(41L * day, 41L * day - hour, "steps")
        val zero = SleepHistoryDiagnostics.Session(41L * day, 41L * day, "steps")
        val tooLong = SleepHistoryDiagnostics.Session(41L * day, 43L * day, "steps")
        val future = session(50L)
        val expired = session(1L)
        val s = d.summarize(listOf(good, reversed, zero, tooLong, future, expired), now, 0L)
        assertEquals(1, s.validSessions)
        assertEquals(1, s.genuineWakes)
        assertNull(s.wakeMinute)
    }

    @Test fun heartRateSummaryRequiresSevenPositiveSessionMeasurements() {
        val records = (40L..46L).mapIndexed { i, dayIndex ->
            session(dayIndex).copy(
                sleepHrP10 = 50 + i,
                daytimeHrP10 = if (i == 6) null else 70 + i,
            )
        }
        val s = d.summarize(records, now, 0L)
        assertEquals(53, s.restingHrBpm)
        assertNull(s.daytimeHrBpm)
    }

    @Test fun p10ExcludesNonFiniteAndNonPositiveHeartRates() {
        val readings = List(30) { 60.0 + it.toDouble() } +
            listOf(Double.NaN, Double.POSITIVE_INFINITY, -4.0, 0.0)
        assertEquals(62, d.p10(readings))
        assertNull(d.p10(listOf(60.0, 70.0)))
    }

    @Test fun circularMeanTreatsMidnightAsMidnightNotNoon() {
        assertEquals(0, d.circularMean(listOf(1430, 10)))
        assertNull(d.circularMean(listOf(0, 720)))
        assertNull(d.circularMean(emptyList()))
    }

    @Test fun timezoneOffsetMovesObservedClockMinutes() {
        assertEquals(0, d.minuteOfDay(23L * hour, hour))
        assertEquals(23 * 60, d.minuteOfDay(21L * hour, 2L * hour))
    }

    @Test fun invalidOffsetsAndClocksFailClosed() {
        val records = (40L..46L).map { session(it) }
        assertEquals(0, d.summarize(records, -1L, 0L).validSessions)
        assertEquals(0, d.summarize(records, now, 19L * hour).validSessions)
    }

    @Test fun negativeAndOutOfRangeClockMinutesCannotBeLearned() {
        assertNull(d.circularMean(listOf(-1, 300)))
        assertNull(d.circularMean(listOf(0, 1440)))
        assertTrue(d.minuteOfDay(0L, -hour) in 0..1439)
    }
}
