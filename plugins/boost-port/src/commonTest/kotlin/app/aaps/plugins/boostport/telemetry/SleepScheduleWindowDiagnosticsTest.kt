package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepScheduleWindowDiagnosticsTest {
    private val windows = SleepScheduleWindowDiagnostics
    private val schedule = SleepScheduleWindowDiagnostics.Schedule(
        nightStartMinute = 22 * 60,
        nightEndMinute = 7 * 60,
        preSleepLeadMinutes = 60,
        sleepInWindowMinutes = 60,
    )

    private fun at(minute: Int) = windows.inspect(schedule, minute)!!

    @Test fun eveningPreSleepLeadStartsBeforeNightButIsNotYetCoreNight() {
        val sample = at(21 * 60)
        assertTrue(sample.preSleep)
        assertFalse(sample.inBaseNight)
        assertTrue(sample.sleepCandidateAllowed)
    }

    @Test fun timeExactlyAtNightStartIsInsideBaseNightNotPreSleep() {
        val sample = at(22 * 60)
        assertTrue(sample.inBaseNight)
        assertFalse(sample.preSleep)
        assertTrue(sample.remainInNightHold)
    }

    @Test fun earlyCandidateWindowExtendsNinetyMinutesBeforeNightStart() {
        assertFalse(at(20 * 60 + 29).sleepCandidateAllowed)
        assertTrue(at(20 * 60 + 30).sleepCandidateAllowed)
        assertTrue(at(21 * 60).sleepCandidateAllowed)
    }

    @Test fun preSleepWindowDoesNotExtendPastScheduledNightStart() {
        assertFalse(at(20 * 60 + 59).preSleep)
        assertTrue(at(21 * 60).preSleep)
        assertTrue(at(21 * 60 + 59).preSleep)
        assertFalse(at(22 * 60).preSleep)
    }

    @Test fun scheduledWakeGraceBeginsNinetyMinutesBeforeEnd() {
        assertFalse(at(5 * 60 + 29).nearScheduledWake)
        assertTrue(at(5 * 60 + 30).nearScheduledWake)
        assertTrue(at(6 * 60 + 59).nearScheduledWake)
    }

    @Test fun exactNightEndTransitionsFromBaseNightToLieIn() {
        val sample = at(7 * 60)
        assertFalse(sample.inBaseNight)
        assertFalse(sample.sleepCandidateAllowed)
        assertFalse(sample.nearScheduledWake)
        assertTrue(sample.inLieIn)
        assertTrue(sample.remainInNightHold)
    }

    @Test fun lieInAllowsContinuationButNotFreshSleepCandidacy() {
        val sample = at(7 * 60 + 30)
        assertTrue(sample.inLieIn)
        assertTrue(sample.remainInNightHold)
        assertFalse(sample.sleepCandidateAllowed)
        assertFalse(sample.preSleep)
    }

    @Test fun nightHoldClosesAtExclusiveLieInEnd() {
        val before = at(7 * 60 + 59)
        val after = at(8 * 60)
        assertTrue(before.remainInNightHold)
        assertFalse(after.remainInNightHold)
        assertFalse(after.inLieIn)
    }

    @Test fun lieInDisabledClosesHoldAtConfiguredMorningBoundary() {
        val old = schedule.copy(sleepInWindowMinutes = 0)
        val end = windows.inspect(old, 7 * 60)!!
        assertFalse(end.inLieIn)
        assertFalse(end.remainInNightHold)
        assertFalse(end.inBaseNight)
    }

    @Test fun scheduleWindowCalculationsWorkAcrossMidnight() {
        assertTrue(at(23 * 60).inBaseNight)
        assertTrue(at(0).inBaseNight)
        assertTrue(at(2 * 60).inBaseNight)
        assertFalse(at(12 * 60).inBaseNight)
    }

    @Test fun invalidClockOrOverlappingMorningWindowAbstains() {
        assertNull(windows.inspect(schedule, -1))
        assertNull(windows.inspect(schedule, 1440))
        assertNull(windows.inspect(schedule.copy(nightStartMinute = 1440), 1))
        assertNull(windows.inspect(schedule.copy(nightEndMinute = 22 * 60), 1))
        assertNull(windows.inspect(schedule.copy(preSleepLeadMinutes = 91), 1))
        assertNull(windows.inspect(schedule.copy(sleepInWindowMinutes = 721), 1))
    }

    @Test fun zeroPreSleepLeadDisablesWarmupWithoutDisablingNight() {
        val zero = schedule.copy(preSleepLeadMinutes = 0)
        assertFalse(windows.inspect(zero, 21 * 60)!!.preSleep)
        assertTrue(windows.inspect(zero, 22 * 60)!!.inBaseNight)
    }
}
