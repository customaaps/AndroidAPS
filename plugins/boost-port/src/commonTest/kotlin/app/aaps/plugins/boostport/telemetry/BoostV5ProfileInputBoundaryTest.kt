package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BoostV5ProfileInputBoundaryTest {
    private val boundary = BoostV5ProfileInputBoundary
    private val sample = BoostV5ProfileInputBoundary.Source(
        minBgMgdl = 90.0, targetBgMgdl = 100.0, maxBgMgdl = 110.0,
        currentBasalUnitsPerHour = 0.8, recentLowBgMgdl = 82.0,
        steps5Min = 12, steps15Min = 35, steps30Min = 80, steps60Min = 125,
        exerciseActive = false, inPostExerciseWindow = true,
    )

    @Test fun coherentInputsCanBeCopiedForOfflineInspection() {
        val result = boundary.inspect(sample)
        assertEquals(BoostV5ProfileInputBoundary.Finding.VALID, result.finding)
        assertNotNull(result.snapshot)
        assertEquals(listOf(12, 35, 80, 125), result.snapshot.steps)
    }

    @Test fun nonFiniteValuesFailClosed() {
        val result = boundary.inspect(sample.copy(recentLowBgMgdl = Double.NaN))
        assertEquals(BoostV5ProfileInputBoundary.Finding.INVALID_NUMBER, result.finding)
        assertNull(result.snapshot)
    }

    @Test fun invertedTargetOrderingFailsClosed() {
        assertEquals(BoostV5ProfileInputBoundary.Finding.INCONSISTENT_TARGET,
            boundary.inspect(sample.copy(targetBgMgdl = 120.0)).finding)
    }

    @Test fun decreasingCumulativeStepsFailClosed() {
        assertEquals(BoostV5ProfileInputBoundary.Finding.INVALID_STEPS,
            boundary.inspect(sample.copy(steps15Min = 3)).finding)
    }

    @Test fun negativeBasalFailsClosed() {
        assertEquals(BoostV5ProfileInputBoundary.Finding.INVALID_RANGE,
            boundary.inspect(sample.copy(currentBasalUnitsPerHour = -0.1)).finding)
    }
}
