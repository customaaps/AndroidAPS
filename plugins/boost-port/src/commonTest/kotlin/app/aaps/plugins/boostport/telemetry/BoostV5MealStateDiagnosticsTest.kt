package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoostV5MealStateDiagnosticsTest {
    private val replay = BoostV5MealStateDiagnostics
    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L

    private fun input(
        score: Double = 0.6,
        offset: Double = 50.0,
        delta: Double = 3.0,
        accel: Double = 3.0,
        time: Long = t0,
        declining: Boolean = false,
        fast: Boolean = false,
        ready: Boolean = false,
        adequate: Boolean = true,
        announced: Boolean = false,
        asleep: Boolean = false,
        exercising: Boolean = false,
    ) = BoostV5MealStateDiagnostics.Input(
        score = score,
        eventualBg = 140.0 + offset,
        targetBg = 140.0,
        delta = delta,
        deltaAcceleration = accel,
        deltaDeclining = declining,
        nowMs = time,
        fastConfirmEnabled = fast,
        scoreReadyStreak = ready,
        legacyDoseAdequacyGatePassed = adequate,
        mealAnnounced = announced,
        asleep = asleep,
        exerciseActive = exercising,
    )

    private fun state(
        phase: BoostV5MealStateDiagnostics.Phase = BoostV5MealStateDiagnostics.Phase.IDLE,
        age: Int = 0,
        locked: Boolean = false,
        lastCommit: Long = 0L,
    ) = BoostV5MealStateDiagnostics.State(
        phase = phase, ageCycles = age, committedInSession = locked,
        lastCommitMs = lastCommit,
    )

    @Test fun idleEntersObservingWithoutCommittingOnNormalScore() {
        val next = replay.step(state(), input())!!
        assertEquals(BoostV5MealStateDiagnostics.Phase.OBSERVING, next.phase)
        assertEquals(0, next.ageCycles)
        assertEquals(0.6, next.peakScore)
        assertEquals(50.0, next.peakOffset)
        assertFalse(next.committedInSession)
    }

    @Test fun wallClockAgeDoesNotAdvanceAtOneMinuteCadence() {
        val entered = replay.step(state(), input())!!
        val afterOne = replay.step(entered, input(time = t0 + minute))!!
        val afterFour = replay.step(afterOne, input(time = t0 + 4 * minute))!!
        assertEquals(0, afterOne.ageCycles)
        assertEquals(1, afterFour.ageCycles)
        assertEquals(t0 + 4 * minute, afterFour.lastAgeMs)
    }

    @Test fun ordinarySlowConfirmationWaitsForAgeGate() {
        var s = replay.step(state(), input())!!
        s = replay.step(s, input(time = t0 + 4 * minute))!!
        s = replay.step(s, input(time = t0 + 8 * minute))!!
        assertEquals(BoostV5MealStateDiagnostics.Phase.OBSERVING, s.phase)
        assertEquals(2, s.ageCycles)
        s = replay.step(s, input(time = t0 + 12 * minute))!!
        assertEquals(BoostV5MealStateDiagnostics.Phase.CONFIRMED, s.phase)
        assertTrue(s.committedInSession)
    }

    @Test fun sustainedScoreCanConfirmOneAgeTickEarly() {
        var s = replay.step(state(), input())!!
        s = replay.step(s, input(time = t0 + 4 * minute))!!
        s = replay.step(s, input(time = t0 + 8 * minute, ready = true))!!
        assertEquals(BoostV5MealStateDiagnostics.Phase.CONFIRMED, s.phase)
    }

    @Test fun confirmMustSatisfyHistoricAdequacyPredicate() {
        var s = replay.step(state(), input())!!
        repeat(3) { i ->
            s = replay.step(s, input(time = t0 + (i + 1) * 4L * minute, adequate = false))!!
        }
        assertEquals(BoostV5MealStateDiagnostics.Phase.OBSERVING, s.phase)
        assertFalse(s.committedInSession)
    }

    @Test fun confirmedLastsExactlyOneReplayCycle() {
        val confirmed = replay.step(state(), input(score = 0.7, delta = 6.0,
            accel = 10.0, fast = true))!!
        assertEquals(BoostV5MealStateDiagnostics.Phase.CONFIRMED, confirmed.phase)
        val after = replay.step(confirmed, input(time = t0 + 5 * minute))!!
        assertEquals(BoostV5MealStateDiagnostics.Phase.COMMITTED, after.phase)
        assertTrue(after.committedInSession)
    }

    @Test fun announcedMealBypassesCatchUpConfirmedLabel() {
        val next = replay.step(state(), input(score = 0.7, delta = 6.0,
            accel = 10.0, fast = true, announced = true))!!
        assertEquals(BoostV5MealStateDiagnostics.Phase.COMMITTED, next.phase)
        assertTrue(next.committedInSession)
    }

    @Test fun sleepOrExerciseBlocksFastConfirm() {
        val sleeping = replay.step(state(), input(score = 0.7, delta = 6.0,
            accel = 10.0, fast = true, asleep = true))!!
        val exercise = replay.step(state(), input(score = 0.7, delta = 6.0,
            accel = 10.0, fast = true, exercising = true))!!
        assertEquals(BoostV5MealStateDiagnostics.Phase.OBSERVING, sleeping.phase)
        assertEquals(BoostV5MealStateDiagnostics.Phase.OBSERVING, exercise.phase)
    }

    @Test fun aFallBelowScoreThresholdEndsOldObservingRun() {
        val aged = BoostV5MealStateDiagnostics.State(
            phase = BoostV5MealStateDiagnostics.Phase.OBSERVING,
            ageCycles = 2, peakScore = 0.8, peakOffset = 80.0,
        )
        val next = replay.step(aged, input(score = 0.35))!!
        assertEquals(BoostV5MealStateDiagnostics.Phase.IDLE, next.phase)
    }

    @Test fun oldPeaksExpireAfterThirtyMinutesRatherThanConfirming() {
        val aged = BoostV5MealStateDiagnostics.State(
            phase = BoostV5MealStateDiagnostics.Phase.OBSERVING,
            ageCycles = 2, peakScore = 0.8, peakOffset = 70.0,
            peakScoreAtMs = t0, peakOffsetAtMs = t0,
        )
        assertTrue(replay.eligibleExceptLegacyDoseGate(
            aged, input(score = 0.4, offset = 10.0, time = t0 + 29 * minute)))
        assertFalse(replay.eligibleExceptLegacyDoseGate(
            aged, input(score = 0.4, offset = 10.0, time = t0 + 31 * minute)))
    }

    @Test fun committedOnlyBacksOffOnDecelerationPlusDecliningHistory() {
        val committed = state(BoostV5MealStateDiagnostics.Phase.COMMITTED, locked = true,
            lastCommit = t0)
        val flat = replay.step(committed, input(time = t0 + 5 * minute,
            accel = -8.0, declining = false))!!
        val falling = replay.step(committed, input(time = t0 + 5 * minute,
            accel = -8.0, declining = true))!!
        assertEquals(BoostV5MealStateDiagnostics.Phase.COMMITTED, flat.phase)
        assertEquals(BoostV5MealStateDiagnostics.Phase.RECOVERING, falling.phase)
    }

    @Test fun recoveringMayReenterCommittedButNotSecondConfirmed() {
        val recovering = state(BoostV5MealStateDiagnostics.Phase.RECOVERING,
            age = 1, locked = true, lastCommit = t0)
        val secondRise = replay.step(recovering, input(time = t0 + 10 * minute,
            delta = 5.0, accel = 11.0, offset = 45.0))!!
        assertEquals(BoostV5MealStateDiagnostics.Phase.COMMITTED, secondRise.phase)
        assertTrue(secondRise.committedInSession)
    }

    @Test fun sessionLockSurvivesRecoveringToIdle() {
        val recovering = state(BoostV5MealStateDiagnostics.Phase.RECOVERING,
            age = 2, locked = true, lastCommit = t0)
        val next = replay.step(recovering, input(time = t0 + 10 * minute,
            delta = -1.0, score = 0.1))!!
        assertEquals(BoostV5MealStateDiagnostics.Phase.IDLE, next.phase)
        assertTrue(next.committedInSession)
        val fastAgain = replay.step(next, input(time = t0 + 15 * minute,
            score = 0.8, fast = true, delta = 10.0, accel = 20.0))!!
        assertEquals(BoostV5MealStateDiagnostics.Phase.OBSERVING, fastAgain.phase)
        assertTrue(fastAgain.committedInSession)
    }

    @Test fun ninetyMinuteExpiryReleasesLock() {
        val locked = state(locked = true, lastCommit = t0)
        val released = replay.releaseLock(locked, t0 + 90 * minute, delta = 5.0)
        assertFalse(released.committedInSession)
    }

    @Test fun sustainedNonPositiveRunMayEndSessionAfterThirtyMinutes() {
        val locked = BoostV5MealStateDiagnostics.State(
            committedInSession = true, lastCommitMs = t0,
            nonPositiveStartMs = t0 + 5 * minute,
        )
        val tooSoon = replay.releaseLock(locked, t0 + 34 * minute, 0.0)
        val enough = replay.releaseLock(locked, t0 + 35 * minute, 0.0)
        assertTrue(tooSoon.committedInSession)
        assertFalse(enough.committedInSession)
    }

    @Test fun resetOnProfileSwitchRetainsHistoricCommitLock() {
        val s = state(BoostV5MealStateDiagnostics.Phase.OBSERVING, age = 2,
            locked = true, lastCommit = t0)
        val (reset, didReset) = replay.resetIfNeeded(s, profileSwitched = true)
        assertTrue(didReset)
        assertEquals(BoostV5MealStateDiagnostics.Phase.IDLE, reset.phase)
        assertTrue(reset.committedInSession)
        assertEquals(t0, reset.lastCommitMs)
    }

    @Test fun staleStateClockDetectionIsAbsoluteAndResetIsSeparate() {
        val s = BoostV5MealStateDiagnostics.State(lastAgeMs = t0)
        assertEquals(35.0, replay.staleStateMinutes(s, t0 + 35 * minute))
        assertEquals(35.0, replay.staleStateMinutes(s, t0 - 35 * minute))
        assertTrue(replay.resetIfNeeded(s, timeJumpMinutes = 35.0).second)
        assertFalse(replay.resetIfNeeded(s, timeJumpMinutes = 30.0).second)
    }

    @Test fun historicalFastConfirmRequiresPostLowRecovery() {
        assertFalse(replay.fastConfirmAllowed(true, 79.9))
        assertTrue(replay.fastConfirmAllowed(true, 80.0))
        assertFalse(replay.fastConfirmAllowed(false, 100.0))
        assertFalse(replay.fastConfirmAllowed(true, Double.NaN))
    }

    @Test fun historicalFloorKeepsRaisedCommittedCapFromTightening() {
        assertEquals(0.5, replay.historicalConfirmFloor(1.5, 3.0))
        assertEquals(0.25, replay.historicalConfirmFloor(0.25, 3.0))
        assertEquals(0.4, replay.historicalConfirmFloor(1.0, 0.5))
        assertNull(replay.historicalConfirmFloor(-1.0, 1.0))
    }

    @Test fun deltaDecliningRequiresAtLeastThreeOrderedFiniteSamples() {
        assertTrue(replay.deltaDeclining(listOf(10.0, 7.0, 2.0)))
        assertFalse(replay.deltaDeclining(listOf(10.0, 11.0, 2.0)))
        assertFalse(replay.deltaDeclining(listOf(10.0, 7.0)))
        assertFalse(replay.deltaDeclining(listOf(10.0, Double.NaN, 3.0)))
    }

    @Test fun invalidClinicalInputsCannotAdvanceReplayState() {
        assertNull(replay.step(state(), input(score = Double.NaN)))
        assertNull(replay.step(state(), input(offset = Double.POSITIVE_INFINITY)))
        assertNull(replay.step(state(), input(time = -1L)))
        assertNull(replay.step(state(), input(score = 1.1)))
        val futureState = BoostV5MealStateDiagnostics.State(lastAgeMs = t0 + minute)
        assertNull(replay.step(futureState, input()))
    }

    @Test fun lowScoreOutsideAVisitKeepsIdleWithoutEmittingCommit() {
        val next = replay.step(state(), input(score = 0.1))!!
        assertNotNull(next)
        assertEquals(BoostV5MealStateDiagnostics.Phase.IDLE, next.phase)
        assertFalse(next.committedInSession)
    }
}
