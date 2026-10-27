package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals

class BoostV5OfflineReplayChangeSummaryTest {
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
    private fun frame(
        t: Long,
        b: BoostV5ProfileInputBoundary.Source = base,
        a: BoostV5ProfileAdditionalInputs.Source = additional,
    ) = BoostV5OfflineProfileReplayTimeline.Frame(t, BoostV5OfflineProfileAssembly.inspect(b, a))

    @Test fun unchangedFramesHaveZeroChanges() {
        val result = BoostV5OfflineReplayChangeSummary.inspect(listOf(frame(1), frame(2)))
        assertEquals(2, result.validatedFrames)
        assertEquals(0, result.changedFrames)
    }

    @Test fun baseAndAdditionalChangeInSameFrameCountOnce() {
        val result = BoostV5OfflineReplayChangeSummary.inspect(listOf(
            frame(1),
            frame(2, b = base.copy(steps60Min = 126),
                a = additional.copy(exerciseSubclass = "recovery")),
        ))
        assertEquals(1, result.changedFrames)
        assertEquals(1, result.baseChanges)
        assertEquals(1, result.additionalChanges)
        assertEquals(1, result.simultaneousChanges)
    }

    @Test fun invalidSequenceProvidesNoPartialCounts() {
        val result = BoostV5OfflineReplayChangeSummary.inspect(listOf(
            frame(1), frame(1, b = base.copy(steps60Min = 126)),
        ))
        assertEquals(BoostV5OfflineProfileReplayTimeline.Finding.NON_MONOTONIC_TIME,
            result.replayFinding)
        assertEquals(0, result.validatedFrames)
        assertEquals(0, result.changedFrames)
    }
}
