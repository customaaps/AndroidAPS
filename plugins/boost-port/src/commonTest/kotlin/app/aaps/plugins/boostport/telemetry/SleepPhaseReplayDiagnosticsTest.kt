package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepPhaseReplayDiagnosticsTest {
    private val d = SleepPhaseReplayDiagnostics
    private val clock = SleepScheduleWindowDiagnostics
    private val origin = 1_700_000_000_000L
    private val minute = 60_000L
    private val schedule = SleepScheduleWindowDiagnostics.Schedule(22 * 60, 7 * 60,
        preSleepLeadMinutes = 60, sleepInWindowMinutes = 60)

    private fun sample(
        localMinute: Int,
        elapsed: Long = 0,
        hr: Boolean = false,
        drought: Boolean = false,
        steps60: Int? = 0,
        steps15: Int? = 0,
        elevated: Boolean = false,
        burst: Boolean = false,
        droughtEstablished: Boolean = false,
        sleepHold: Int = 10,
        wakeHold: Int = 5,
    ) = SleepPhaseReplayDiagnostics.Evidence(
        nowMs = origin + elapsed * minute,
        windows = clock.inspect(schedule, localMinute)!!,
        hrSleepCandidate = hr,
        droughtSleepCandidate = drought,
        averageHrAvailable = hr,
        stepsGrowth60Min = steps60,
        stepsLast15Min = steps15,
        sustainedElevatedHr = elevated,
        resumeBurstCorroborated = burst,
        droughtEstablished = droughtEstablished,
        minSleepHysteresisMinutes = sleepHold,
        minWakeHysteresisMinutes = wakeHold,
    )

    private fun sleeping() = SleepPhaseReplayDiagnostics.State(
        phase = SleepPhaseReplayDiagnostics.Phase.SLEEPING,
        enteredAtMs = origin - 60 * minute,
        sleepEntryReason = "hr",
    )

    @Test fun eveningPreSleepIsOnlyADiagnosticStateNotConfirmedSleep() {
        val result = d.replay(SleepPhaseReplayDiagnostics.State(),
            sample(21 * 60))!!
        assertEquals(SleepPhaseReplayDiagnostics.Phase.PRE_SLEEP, result.state.phase)
        assertTrue(result.transitioned)
        assertNull(result.wakeReason)
    }

    @Test fun earlySleepCandidateNeedsHysteresisBeforePromotion() {
        val first = d.replay(SleepPhaseReplayDiagnostics.State(),
            sample(20 * 60 + 30, hr = true))!!
        assertEquals(SleepPhaseReplayDiagnostics.Phase.AWAKE, first.state.phase)
        val early = d.replay(first.state,
            sample(20 * 60 + 35, elapsed = 5, hr = true))!!
        assertFalse(early.transitioned)
        val confirmed = d.replay(early.state,
            sample(20 * 60 + 40, elapsed = 10, hr = true))!!
        assertEquals(SleepPhaseReplayDiagnostics.Phase.SLEEPING, confirmed.state.phase)
        assertEquals("hr", confirmed.state.sleepEntryReason)
    }

    @Test fun presleepQualificationCanPromoteToSleepingAfterTenMinutes() {
        val warm = d.replay(SleepPhaseReplayDiagnostics.State(),
            sample(21 * 60))!!.state
        val candidate = d.replay(warm, sample(21 * 60 + 1,
            elapsed = 1, drought = true))!!.state
        val ready = d.replay(candidate, sample(21 * 60 + 11,
            elapsed = 11, drought = true))!!
        assertEquals(SleepPhaseReplayDiagnostics.Phase.SLEEPING, ready.state.phase)
        assertEquals("drought", ready.state.sleepEntryReason)
    }

    @Test fun discontinuousSleepSignalsMustRestartHysteresis() {
        val first = d.replay(SleepPhaseReplayDiagnostics.State(),
            sample(20 * 60 + 30, hr = true))!!.state
        val reset = d.replay(first, sample(20 * 60 + 35,
            elapsed = 5, hr = false, drought = false))!!.state
        assertNull(reset.sleepCandidateSinceMs)
        val restart = d.replay(reset, sample(20 * 60 + 40,
            elapsed = 10, hr = true))!!
        assertFalse(restart.transitioned)
    }

    @Test fun strongActivityExitsPreSleepWithoutMarkingGenuineWake() {
        val prev = SleepPhaseReplayDiagnostics.State(
            phase = SleepPhaseReplayDiagnostics.Phase.PRE_SLEEP,
            enteredAtMs = origin - 5 * minute,
        )
        val r = d.replay(prev, sample(21 * 60 + 10, steps60 = 250))!!
        assertEquals(SleepPhaseReplayDiagnostics.Phase.AWAKE, r.state.phase)
        assertNull(r.wakeReason)
    }

    @Test fun scheduledHardExitDoesNotTrainActualWake() {
        val r = d.replay(sleeping(), sample(8 * 60))!!
        assertEquals(SleepPhaseReplayDiagnostics.Phase.AWAKE, r.state.phase)
        assertEquals("boundary", r.wakeReason)
        assertFalse(SleepSessionHistoryDiagnostics.isGenuineWake(r.wakeReason))
    }

    @Test fun morningLieInHoldsSleepUntilMovementOrBoundary() {
        val r = d.replay(sleeping(), sample(7 * 60 + 20))!!
        assertFalse(r.transitioned)
        assertEquals(SleepPhaseReplayDiagnostics.Phase.SLEEPING, r.state.phase)
        val atEnd = d.replay(r.state, sample(8 * 60, elapsed = 40))!!
        assertEquals("boundary", atEnd.wakeReason)
    }

    @Test fun confirmedTransmissionResumeIsImmediateGenuineWake() {
        val r = d.replay(sleeping(), sample(6 * 60, burst = true))!!
        assertTrue(r.transitioned)
        assertEquals("resume", r.wakeReason)
        assertTrue(SleepSessionHistoryDiagnostics.isGenuineWake(r.wakeReason))
    }

    @Test fun unconfirmedCoreNightHrBurstCannotForceWake() {
        val r = d.replay(sleeping(), sample(2 * 60, burst = false))!!
        assertEquals(SleepPhaseReplayDiagnostics.Phase.SLEEPING, r.state.phase)
        assertFalse(r.transitioned)
    }

    @Test fun gentleWakeNeedsScheduledGraceHrAndSteps() {
        val first = d.replay(sleeping(), sample(6 * 60,
            steps15 = 100, elevated = true))!!
        assertFalse(first.transitioned)
        val second = d.replay(first.state, sample(6 * 60 + 5,
            elapsed = 5, steps15 = 100, elevated = true))!!
        assertTrue(second.transitioned)
        assertEquals("hr_steps", second.wakeReason)
    }

    @Test fun coreNightHrRiseIsNotGentleWakeOutsideScheduledGrace() {
        val r = d.replay(sleeping(), sample(2 * 60,
            steps15 = 200, elevated = true))!!
        assertFalse(r.transitioned)
    }

    @Test fun strongStepsWithoutHrNeedDroughtOrLieIn() {
        val core = d.replay(sleeping(), sample(2 * 60, steps60 = 500))!!
        assertFalse(core.transitioned)
        val drought = d.replay(sleeping(), sample(2 * 60,
            steps60 = 500, droughtEstablished = true))!!
        assertFalse(drought.transitioned)
        val next = d.replay(drought.state, sample(2 * 60 + 5, elapsed = 5,
            steps60 = 500, droughtEstablished = true))!!
        assertEquals("steps", next.wakeReason)
    }

    @Test fun lieInStrongStepsCanWakeWithoutHrDrought() {
        val initial = d.replay(sleeping(), sample(7 * 60 + 10,
            steps60 = 300))!!
        assertFalse(initial.transitioned)
        val done = d.replay(initial.state, sample(7 * 60 + 15, elapsed = 5,
            steps60 = 300))!!
        assertEquals("steps", done.wakeReason)
    }

    @Test fun repeatedCycleAtSameMomentDoesNotCompleteHysteresis() {
        val first = d.replay(sleeping(), sample(6 * 60,
            elevated = true, steps15 = 100))!!
        val same = d.replay(first.state, sample(6 * 60,
            elevated = true, steps15 = 100))!!
        assertFalse(same.transitioned)
    }

    @Test fun wakeEvidenceDisappearanceResetsCandidate() {
        val first = d.replay(sleeping(), sample(6 * 60,
            elevated = true, steps15 = 100))!!.state
        val second = d.replay(first, sample(6 * 60 + 2,
            elapsed = 2, elevated = false))!!.state
        assertNull(second.wakeCandidateSinceMs)
    }

    @Test fun malformedTimingAndStepsAbstain() {
        assertNull(d.replay(sleeping(), sample(6 * 60).copy(nowMs = -1)))
        assertNull(d.replay(sleeping(), sample(6 * 60).copy(stepsGrowth60Min = -1)))
        assertNull(d.replay(sleeping(), sample(6 * 60).copy(
            minWakeHysteresisMinutes = -1)))
        assertNull(d.replay(sleeping(), sample(6 * 60).copy(
            minSleepHysteresisMinutes = 300)))
        assertNull(d.replay(sleeping().copy(enteredAtMs = origin + minute),
            sample(6 * 60)))
    }
}
