package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepSessionHistoryDiagnosticsTest {
    private val history = SleepSessionHistoryDiagnostics
    private val day = 86_400_000L
    private val hour = 3_600_000L
    private val origin = 100L * day

    @Test fun confirmedSleepStartOpensOnlyOneSession() {
        val initial = SleepSessionHistoryDiagnostics.History()
        val begun = history.start(initial, origin)!!
        assertEquals(origin, begun.openSleepStartMs)
        assertTrue(begun.sessions.isEmpty())
        assertNull(history.start(begun, origin + hour))
        assertNull(initial.openSleepStartMs)
    }

    @Test fun wakeClosesOpenSessionAndClearsPendingStart() {
        val begun = history.start(SleepSessionHistoryDiagnostics.History(), origin)!!
        val closed = history.wake(begun, origin + 8 * hour, wakeReason = "hr_steps")!!
        assertTrue(closed.recorded)
        assertTrue(closed.wakeIsGenuine)
        assertNull(closed.history.openSleepStartMs)
        assertEquals(origin + 8 * hour, closed.history.sessions.single().wakeMs)
        assertEquals(origin, closed.history.sessions.single().sleepStartMs)
    }

    @Test fun wakeWithoutStartCannotFabricateASession() {
        val empty = SleepSessionHistoryDiagnostics.History()
        val closed = history.wake(empty, origin)!!
        assertFalse(closed.recorded)
        assertTrue(closed.history.sessions.isEmpty())
    }

    @Test fun duplicateWakeDoesNotAppendSecondSession() {
        val initial = history.start(SleepSessionHistoryDiagnostics.History(), origin)!!
        val first = history.wake(initial, origin + 8 * hour, wakeReason = "hr_steps")!!
        val second = history.wake(first.history, origin + 9 * hour)!!
        assertFalse(second.recorded)
        assertEquals(1, second.history.sessions.size)
    }

    @Test fun scheduleBoundaryCannotTrainLearnedWakeTime() {
        var record = SleepSessionHistoryDiagnostics.History()
        repeat(7) { index ->
            val start = origin + index * day
            record = history.start(record, start)!!
            record = history.wake(record, start + 8 * hour, wakeReason = "boundary")!!.history
        }
        val summary = history.summarize(record, origin + 7 * day, 0L)!!
        assertEquals(7, summary.validSessions)
        assertNotNull(summary.onsetMinute)
        assertNull(summary.wakeMinute)
        assertFalse(history.isGenuineWake("boundary"))
        assertFalse(history.isGenuineWake(null))
    }

    @Test fun stepsOnlyWakesAreGenuineWhenHrFeedMissing() {
        assertTrue(history.isGenuineWake("hr_steps"))
        assertTrue(history.isGenuineWake("resume"))
        assertTrue(history.isGenuineWake("steps"))
        val open = history.start(SleepSessionHistoryDiagnostics.History(), origin)!!
        val ended = history.wake(open, origin + 8 * hour, wakeReason = "steps")!!
        assertTrue(ended.wakeIsGenuine)
    }

    @Test fun sevenGenuineWakesCanTrainObservedWakeTime() {
        var record = SleepSessionHistoryDiagnostics.History()
        repeat(7) { i ->
            val start = origin + i * day
            record = history.start(record, start)!!
            record = history.wake(record, start + 8 * hour, wakeReason = "resume")!!.history
        }
        val summary = history.summarize(record, origin + 7 * day, 0L)!!
        assertEquals(480, summary.wakeMinute)
        assertEquals(0, summary.onsetMinute)
    }

    @Test fun sessionCloseRecordsOnlyQualityControlledHrPercentiles() {
        val open = history.start(SleepSessionHistoryDiagnostics.History(), origin)!!
        val fewer = history.wake(open, origin + 8 * hour,
            sleepHrBpms = List(29) { 61.0 },
            daytimeHrBpms = List(30) { 90.0 },
            wakeReason = "hr_steps")!!.history.sessions.single()
        assertNull(fewer.sleepHrP10)
        assertEquals(90, fewer.daytimeHrP10)
        val enough = history.wake(open, origin + 8 * hour,
            sleepHrBpms = List(30) { 61.0 })!!.history.sessions.single()
        assertEquals(61, enough.sleepHrP10)
    }

    @Test fun closeRejectsReverseTimeAndOverlongSession() {
        val open = history.start(SleepSessionHistoryDiagnostics.History(), origin)!!
        assertNull(history.wake(open, origin - 1))
        assertNull(history.wake(open, origin + day + 1))
        assertEquals(origin, open.openSleepStartMs)
    }

    @Test fun futureAndBackwardsSleepStartsAreRejected() {
        assertNull(history.start(SleepSessionHistoryDiagnostics.History(), 0L))
        val open = history.start(SleepSessionHistoryDiagnostics.History(), origin)!!
        val closed = history.wake(open, origin + 8 * hour)!!.history
        assertNull(history.start(closed, origin + hour))
    }

    @Test fun oldSessionsArePrunedAtCloseWithinTwentyEightDayWindow() {
        val old = SleepHistoryDiagnostics.Session(10L * day, 10L * day + hour)
        val recent = SleepHistoryDiagnostics.Session(99L * day, 99L * day + hour)
        val withOpen = SleepSessionHistoryDiagnostics.History(
            sessions = listOf(old, recent), openSleepStartMs = origin,
        )
        val result = history.wake(withOpen, origin + 8 * hour)!!
        assertEquals(2, result.history.sessions.size)
        assertEquals(99L * day, result.history.sessions.first().sleepStartMs)
    }

    @Test fun lastWakeReportsMaxTimestampNotInsertionOrder() {
        val h = SleepSessionHistoryDiagnostics.History(
            sessions = listOf(
                SleepHistoryDiagnostics.Session(origin + 3 * day, origin + 3 * day + hour),
                SleepHistoryDiagnostics.Session(origin + day, origin + day + hour),
            )
        )
        assertEquals(origin + 3 * day + hour, history.lastWakeMs(h))
    }

    @Test fun unexpectedWakeLabelAndCorruptHistoryFailClosed() {
        val open = history.start(SleepSessionHistoryDiagnostics.History(), origin)!!
        assertNull(history.wake(open, origin + hour, wakeReason = "guess"))
        assertNull(history.start(SleepSessionHistoryDiagnostics.History(
            sessions = listOf(SleepHistoryDiagnostics.Session(origin, origin - 1L))
        ), origin + day))
    }
}
