package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals

class BoostV5OfflineProfileChangeEventsTest {
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
    private fun frame(t: Long,
        b: BoostV5ProfileInputBoundary.Source = base,
        a: BoostV5ProfileAdditionalInputs.Source = additional,
    ) = BoostV5OfflineProfileReplayTimeline.Frame(
        t, BoostV5OfflineProfileAssembly.inspect(b, a)
    )

    @Test fun unchangedProfileProducesNoEvents() {
        val result = BoostV5OfflineProfileChangeEvents.inspect(listOf(frame(1), frame(2)))
        assertEquals(0, result.changes.size)
        assertEquals(2, result.validatedFrameCount)
    }

    @Test fun baseAndAdditionalChangesHaveDistinctMarkers() {
        val result = BoostV5OfflineProfileChangeEvents.inspect(listOf(
            frame(1), frame(2, b = base.copy(steps60Min = 126)),
            frame(3, b = base.copy(steps60Min = 126),
                a = additional.copy(exerciseSubclass = "recovery"))
        ))
        assertEquals(2, result.changes.size)
        assertEquals(true, result.changes[0].baseChanged)
        assertEquals(false, result.changes[0].additionalChanged)
        assertEquals(false, result.changes[1].baseChanged)
        assertEquals(true, result.changes[1].additionalChanged)
    }

    @Test fun invalidReplayProducesNoPartialEvents() {
        val result = BoostV5OfflineProfileChangeEvents.inspect(listOf(
            frame(1), frame(2, b = base.copy(steps5Min = -1))
        ))
        assertEquals(BoostV5OfflineProfileReplayTimeline.Finding.INVALID_FRAME,
            result.replayFinding)
        assertEquals(emptyList(), result.changes)
        assertEquals(0, result.validatedFrameCount)
    }
}
