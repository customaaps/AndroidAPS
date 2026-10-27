package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StepBucketWindowDiagnosticsTest {
    private val windows = StepBucketWindowDiagnostics
    private val bucket = StepBucketWindowDiagnostics.BUCKET_MS

    private fun accept(
        state: StepBucketWindowDiagnostics.State,
        bucketIndex: Long,
        raw: Long,
    ) = windows.accept(state, raw, bucketIndex * bucket)!!

    @Test fun initialSensorCallbackEstablishesBaselineNotStepCount() {
        val original = StepBucketWindowDiagnostics.State()
        val first = accept(original, 1L, 2000L)
        assertTrue(first.totalsByBucket.isEmpty())
        val snapshot = windows.completedWindow(first, 2 * bucket, 5)!!
        assertTrue(snapshot.feedKnown)
        assertEquals(0, snapshot.steps)
        assertFalse(windows.completedWindow(original, 2 * bucket, 5)!!.feedKnown)
        assertNull(windows.completedWindow(original, 2 * bucket, 5)!!.steps)
    }

    @Test fun completedFiveMinuteBucketExcludesCurrentBucket() {
        var state = accept(StepBucketWindowDiagnostics.State(), 1L, 100L)
        state = accept(state, 2L, 130L)
        state = accept(state, 3L, 170L)
        val result = windows.completedWindow(state, 3 * bucket, 5)!!
        assertEquals(30, result.steps)
        assertEquals(1, result.bucketCount)
    }

    @Test fun tenAndFifteenMinuteWindowsRespectStrictLegacyCutoff() {
        var state = accept(StepBucketWindowDiagnostics.State(), 1L, 100L)
        state = accept(state, 2L, 120L)
        state = accept(state, 3L, 150L)
        state = accept(state, 4L, 190L)
        state = accept(state, 5L, 240L)
        assertEquals(70, windows.completedWindow(state, 5 * bucket, 10)!!.steps)
        assertEquals(90, windows.completedWindow(state, 5 * bucket, 15)!!.steps)
    }

    @Test fun largeSensorJumpIsIgnoredAndNewRawBecomesBaseline() {
        var state = accept(StepBucketWindowDiagnostics.State(), 0L, 100L)
        state = accept(state, 1L, 100_000L)
        state = accept(state, 2L, 100_030L)
        val result = windows.completedWindow(state, 3 * bucket, 10)!!
        assertEquals(30, result.steps)
    }

    @Test fun sensorRebootDropDoesNotInventNegativeOrNewMovement() {
        var state = accept(StepBucketWindowDiagnostics.State(), 0L, 6000L)
        state = accept(state, 1L, 15L)
        state = accept(state, 2L, 50L)
        assertEquals(35, windows.completedWindow(state, 3 * bucket, 15)!!.steps)
    }

    @Test fun bucketAccumulatesSeveralPlausibleIncrementEvents() {
        var state = accept(StepBucketWindowDiagnostics.State(), 0L, 100L)
        state = windows.accept(state, 110L, bucket + 100L)!!
        state = windows.accept(state, 125L, bucket + 200L)!!
        state = windows.accept(state, 140L, bucket + 300L)!!
        assertEquals(40, windows.completedWindow(state, 2 * bucket, 5)!!.steps)
    }

    @Test fun zeroMotionIsDifferentFromNoSensorFeed() {
        val none = windows.completedWindow(StepBucketWindowDiagnostics.State(),
            3 * bucket, 15)!!
        assertNull(none.steps)
        val observed = accept(StepBucketWindowDiagnostics.State(), 0L, 100L)
        assertEquals(0, windows.completedWindow(observed, 3 * bucket, 15)!!.steps)
    }

    @Test fun oldBucketsAreRemovedFromRollingHistory() {
        var state = accept(StepBucketWindowDiagnostics.State(), 0L, 0L)
        for (i in 1L..30L) {
            state = accept(state, i, i * 10L)
        }
        assertTrue(state.totalsByBucket.keys.all { it >= 10L })
        assertTrue(state.totalsByBucket.size <= 21)
    }

    @Test fun backwardsCallbackTimeIsRejectedWithoutMutatingPriorState() {
        val first = accept(StepBucketWindowDiagnostics.State(), 5L, 500L)
        assertNull(windows.accept(first, 510L, 4 * bucket))
        assertEquals(500L, first.previousRawCount)
    }

    @Test fun diagnosticWindowRejectsFutureLastEvent() {
        val observed = accept(StepBucketWindowDiagnostics.State(), 5L, 200L)
        assertNull(windows.completedWindow(observed, 4 * bucket, 5))
        assertNull(windows.completedWindow(observed, 6 * bucket, 7))
    }

    @Test fun oneStepCounterResetNeverSynthesizesZeroAsSensorObservation() {
        val state = accept(StepBucketWindowDiagnostics.State(), 0L, 5000L)
        assertNull(windows.accept(state, -1L, bucket))
        assertNull(windows.accept(state, 4900L, -1L))
    }

    @Test fun historicalBucketSumSaturatesAtIntMaximum() {
        val state = StepBucketWindowDiagnostics.State(
            previousRawCount = 50L,
            lastEventMs = 4 * bucket,
            totalsByBucket = mapOf(1L to Int.MAX_VALUE, 2L to Int.MAX_VALUE),
        )
        val result = windows.completedWindow(state, 4 * bucket, 15)!!
        assertEquals(Int.MAX_VALUE, result.steps)
    }
}
