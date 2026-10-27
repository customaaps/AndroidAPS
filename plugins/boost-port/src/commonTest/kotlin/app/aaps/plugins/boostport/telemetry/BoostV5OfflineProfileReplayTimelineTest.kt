package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals

class BoostV5OfflineProfileReplayTimelineTest {
    private val base = BoostV5ProfileInputBoundary.Source(
        minBgMgdl = 90.0, targetBgMgdl = 100.0, maxBgMgdl = 110.0,
        currentBasalUnitsPerHour = 0.8, recentLowBgMgdl = 82.0,
        steps5Min = 12, steps15Min = 35, steps30Min = 80, steps60Min = 125,
        exerciseActive = false, inPostExerciseWindow = false,
    )
    private val additional = BoostV5ProfileAdditionalInputs.Source(
        variableSensitivity = 42.0, totalDailyDoseUnits = 25.0,
        insulinDivisor = 55, dynamicIsfVelocity = 0.5, insulinPeakMinutes = 50,
        boostActive = false, dynamicIsfMode = true, exerciseSubclass = "",
    )
    private fun frame(timestamp: Long, baseInput: BoostV5ProfileInputBoundary.Source = base) =
        BoostV5OfflineProfileReplayTimeline.Frame(
            timestamp, BoostV5OfflineProfileAssembly.inspect(baseInput, additional)
        )

    @Test fun emptySequenceIsNotReady() {
        assertEquals(BoostV5OfflineProfileReplayTimeline.Finding.EMPTY,
            BoostV5OfflineProfileReplayTimeline.inspect(emptyList()).finding)
    }

    @Test fun monotonicValidFramesPreserveChangeCount() {
        val report = BoostV5OfflineProfileReplayTimeline.inspect(listOf(
            frame(1000), frame(2000), frame(3000, base.copy(steps60Min = 126))
        ))
        assertEquals(BoostV5OfflineProfileReplayTimeline.Finding.READY, report.finding)
        assertEquals(1, report.profileChanges)
    }

    @Test fun negativeFirstTimestampIsRejected() {
        val report = BoostV5OfflineProfileReplayTimeline.inspect(listOf(frame(-1)))
        assertEquals(BoostV5OfflineProfileReplayTimeline.Finding.NON_MONOTONIC_TIME, report.finding)
        assertEquals(0, report.firstInvalidIndex)
    }

    @Test fun decreasingTimestampHasPriorityOverInvalidProfile() {
        val report = BoostV5OfflineProfileReplayTimeline.inspect(listOf(
            frame(2000), frame(1000, base.copy(steps5Min = -1))
        ))
        assertEquals(BoostV5OfflineProfileReplayTimeline.Finding.NON_MONOTONIC_TIME, report.finding)
        assertEquals(1, report.firstInvalidIndex)
    }

    @Test fun oversizedReplayIsRejectedBeforeProcessingFrames() {
        val oversized = List(10_001) { index -> frame(index.toLong()) }
        val report = BoostV5OfflineProfileReplayTimeline.inspect(oversized)
        assertEquals(BoostV5OfflineProfileReplayTimeline.Finding.TOO_MANY_FRAMES, report.finding)
        assertEquals(0, report.inspectedFrames)
    }

    @Test fun boundarySizedReplayIsAccepted() {
        val valid = List(10_000) { index -> frame(index.toLong()) }
        val report = BoostV5OfflineProfileReplayTimeline.inspect(valid)
        assertEquals(BoostV5OfflineProfileReplayTimeline.Finding.READY, report.finding)
        assertEquals(10_000, report.inspectedFrames)
    }

    @Test fun repeatedTimeIsRejected() {
        val report = BoostV5OfflineProfileReplayTimeline.inspect(listOf(frame(1000), frame(1000)))
        assertEquals(BoostV5OfflineProfileReplayTimeline.Finding.NON_MONOTONIC_TIME, report.finding)
        assertEquals(1, report.firstInvalidIndex)
    }

    @Test fun invalidIntermediateFrameIsRejected() {
        val report = BoostV5OfflineProfileReplayTimeline.inspect(listOf(
            frame(1000), frame(2000, base.copy(steps15Min = -1)), frame(3000)
        ))
        assertEquals(BoostV5OfflineProfileReplayTimeline.Finding.INVALID_FRAME, report.finding)
        assertEquals(1, report.firstInvalidIndex)
    }
}
