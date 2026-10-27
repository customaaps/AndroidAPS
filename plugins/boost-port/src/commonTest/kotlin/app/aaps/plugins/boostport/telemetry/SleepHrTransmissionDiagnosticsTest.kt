package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepHrTransmissionDiagnosticsTest {
    private val d = SleepHrTransmissionDiagnostics
    private val min = 60_000L
    private val now = 1_700_000_000_000L

    private fun sample(agoMin: Long, valid: Boolean = true) =
        SleepHrTransmissionDiagnostics.Sample(now - agoMin * min, valid)

    private fun input(
        minuteOfDay: Int = 180,
        lastReliableAgo: Long = 40,
        steps15: Int? = 0,
        steps60: Int? = 0,
        window: Int = 10,
        readings: List<SleepHrTransmissionDiagnostics.Sample> =
            listOf(sample(3), sample(2), sample(1)),
        lieIn: Int = 60,
    ) = SleepHrTransmissionDiagnostics.Input(
        nowMs = now,
        minuteOfDay = minuteOfDay,
        nightEndMinute = 420,
        sleepInWindowMinutes = lieIn,
        droughtThresholdMinutes = 30,
        freshWindowMinutes = window,
        previouslyReliableSampleMs = now - lastReliableAgo * min,
        stepsLast15Min = steps15,
        cumulativeStepGrowth60Min = steps60,
        readings = readings,
    )

    @Test fun overnightGarminBurstWithoutStepsIsNotAConfirmedWake() {
        val result = d.inspect(input())!!
        assertTrue(result.reliableFeed)
        assertEquals(3, result.freshSamplesLast15Min)
        assertFalse(result.eligibleResumeBurst)
        assertNull(result.corroboration)
    }

    @Test fun coreNightBurstNeedsAtLeastTwentySteps() {
        assertFalse(d.inspect(input(steps15 = 19))!!.eligibleResumeBurst)
        val result = d.inspect(input(steps15 = 20))!!
        assertTrue(result.eligibleResumeBurst)
        assertEquals("steps", result.corroboration)
    }

    @Test fun largeLumpedStepsCorroborateWakeEvenIf15MinuteFeedIsQuiet() {
        val result = d.inspect(input(steps15 = 0, steps60 = 1326))!!
        assertTrue(result.eligibleResumeBurst)
        assertEquals("steps", result.corroboration)
    }

    @Test fun nearScheduledWakeAllowsBurstBeforeStepsArrive() {
        val r = d.inspect(input(minuteOfDay = 350))!!
        assertTrue(r.eligibleResumeBurst)
        assertEquals("scheduled-wake-grace", r.corroboration)
        assertEquals(40L, r.priorDroughtMinutes)
    }

    @Test fun earlyNightDoesNotGetScheduledWakeException() {
        val r = d.inspect(input(minuteOfDay = 329))!!
        assertFalse(r.eligibleResumeBurst)
    }

    @Test fun lieInAfterNightEndAllowsBurstButNotCoreNight() {
        val r = d.inspect(input(minuteOfDay = 450))!!
        assertTrue(r.eligibleResumeBurst)
        assertEquals("lie-in", r.corroboration)
        val disabled = d.inspect(input(minuteOfDay = 450, lieIn = 0))!!
        assertFalse(disabled.eligibleResumeBurst)
    }

    @Test fun strayOneOrTwoSamplesDoNotResetReliableHrClock() {
        val r = d.inspect(input(readings = listOf(sample(1), sample(2))))!!
        assertFalse(r.reliableFeed)
        assertEquals(now - 40 * min, r.nextReliableSampleMs)
        assertEquals(40L, r.currentDroughtMinutes)
        assertFalse(r.eligibleResumeBurst)
    }

    @Test fun reliableBurstUpdatesLastGoodHrClock() {
        val r = d.inspect(input())!!
        assertEquals(now - min, r.nextReliableSampleMs)
        assertEquals(1L, r.currentDroughtMinutes)
        assertEquals(now - min, r.latestFreshSampleMs)
    }

    @Test fun noPriorLiveHrRecordCountsAsEstablishedDrought() {
        val i = input(steps60 = 250).copy(previouslyReliableSampleMs = 0L)
        val r = d.inspect(i)!!
        assertNull(r.priorDroughtMinutes)
        assertTrue(r.eligibleResumeBurst)
    }

    @Test fun recentReliableHrDoesNotCountAsDroughtEvenWithMovement() {
        val r = d.inspect(input(lastReliableAgo = 5, steps15 = 250))!!
        assertFalse(r.eligibleResumeBurst)
    }

    @Test fun backdatedBatchOutsideFreshWindowIsNotLiveBurst() {
        val r = d.inspect(input(readings = listOf(
            sample(25), sample(20), sample(18)
        )))!!
        assertFalse(r.reliableFeed)
        assertNull(r.latestFreshSampleMs)
        assertFalse(r.eligibleResumeBurst)
    }

    @Test fun invalidOrFutureHrSamplesCannotFabricateBurst() {
        val r = d.inspect(input(readings = listOf(
            sample(-1), sample(-2), sample(1, false)
        )))!!
        assertEquals(0, r.freshSamplesLast15Min)
        assertFalse(r.eligibleResumeBurst)
        assertEquals(now - 40 * min, r.nextReliableSampleMs)
    }

    @Test fun absentMovementSignalsAreNotPromotedIntoGenuineWake() {
        val r = d.inspect(input(steps15 = null, steps60 = null))!!
        assertFalse(r.eligibleResumeBurst)
    }

    @Test fun clockAndMalformedInputsFailClosed() {
        assertNull(d.inspect(input().copy(nowMs = -1)))
        assertNull(d.inspect(input().copy(minuteOfDay = 1440)))
        assertNull(d.inspect(input().copy(nightEndMinute = -1)))
        assertNull(d.inspect(input().copy(droughtThresholdMinutes = 0)))
        assertNull(d.inspect(input().copy(stepsLast15Min = -1)))
        assertNull(d.inspect(input().copy(previouslyReliableSampleMs = now + 1)))
        assertNull(d.inspect(input().copy(sleepInWindowMinutes = 1440)))
    }
}
