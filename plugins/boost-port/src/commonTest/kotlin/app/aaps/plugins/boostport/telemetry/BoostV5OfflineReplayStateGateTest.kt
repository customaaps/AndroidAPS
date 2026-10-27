package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoostV5OfflineReplayStateGateTest {
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
    private fun frame(t: Long) = BoostV5OfflineProfileReplayTimeline.Frame(
        t, BoostV5OfflineProfileAssembly.inspect(base, additional)
    )
    private val emptyState = BoostV5StateSnapshotCodec.Snapshot()

    @Test fun validReplayAndEmptyStateAreReady() {
        assertEquals(BoostV5OfflineReplayStateGate.Finding.READY,
            BoostV5OfflineReplayStateGate.inspect(listOf(frame(1_000), frame(2_000)), emptyState).finding)
    }

    @Test fun malformedReplayNeverChecksStateAsValid() {
        val result = BoostV5OfflineReplayStateGate.inspect(listOf(frame(2_000), frame(1_000)), emptyState)
        assertEquals(BoostV5OfflineReplayStateGate.Finding.INVALID_REPLAY, result.finding)
        assertEquals(null, result.stateFinding)
    }

    @Test fun futureStateClockRelativeToFirstFrameIsRejected() {
        val original = emptyState.copy(primerIobUpdatedMs = 1_500L)
        val result = BoostV5OfflineReplayStateGate.inspect(listOf(frame(1_000), frame(2_000)), original)
        assertEquals(BoostV5OfflineReplayStateGate.Finding.FUTURE_STATE_CLOCK, result.finding)
        assertTrue("primerIobUpdatedMs" in result.futureClockFields)
        assertEquals(1_500L, original.primerIobUpdatedMs)
    }

    @Test fun malformedStateCannotPassReplayEntry() {
        val invalid = emptyState.copy(primerIobU = Double.NaN)
        assertEquals(BoostV5OfflineReplayStateGate.Finding.INVALID_STATE,
            BoostV5OfflineReplayStateGate.inspect(listOf(frame(1_000)), invalid).finding)
    }
}
