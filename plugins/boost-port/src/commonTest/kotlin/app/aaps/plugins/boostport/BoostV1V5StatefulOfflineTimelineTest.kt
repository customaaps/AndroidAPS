package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1OfflineCycleMath
import app.aaps.plugins.aps.openAPSBoostV5.V5Inputs
import app.aaps.plugins.boostport.telemetry.BoostV1V5StatefulOfflineTimeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostV1V5StatefulOfflineTimelineTest {
    private fun v5(at: Long) = V5Inputs(
        delta = 0.0, shortAvgDelta = 0.0, deltaAccl = 0.0, bg = 110.0,
        eventualBg = 110.0, targetBg = 110.0, maxDelta = 0.0,
        minGuardBg = 110.0, minGuardThreshold = 70.0,
        deltaHistory = listOf(0.0, 0.0, 0.0),
        iob = 0.0, maxIob = 1.0, baseInsulinReq = 0.0, roundSmbTo = 0.05,
        enableSmbPreChecks = false, mlHypoRisk = null, mlMealLikely = null,
        recentLowBg = 110.0, cumulativeRise30min = 0.0, hour = 12,
        exerciseActive = false, inPostExerciseWindow = false, nowMs = at
    )
    private fun frame(at: Long) = BoostV1V5StatefulOfflineTimeline.Frame(
        at, BoostV1OfflineCycleMath.Input(110.0, 110.0, 110.0, 0.0, 12), v5(at)
    )

    @Test fun emptyAndOutOfOrderBatchesAreNotRunnable() {
        val empty = BoostV1V5StatefulOfflineTimeline.inspect(emptyList())
        assertEquals(BoostV1V5StatefulOfflineTimeline.Finding.EMPTY, empty.finding)
        assertFalse(empty.canDeliverInsulin)
        val bad = BoostV1V5StatefulOfflineTimeline.inspect(listOf(frame(100), frame(100)))
        assertEquals(BoostV1V5StatefulOfflineTimeline.Finding.INVALID_CLOCK, bad.finding)
        assertEquals(1, bad.firstInvalidIndex)
    }

    @Test fun invalidV1IsRejectedBeforeV5EngineRuns() {
        val bad = frame(100).copy(v1 = BoostV1OfflineCycleMath.Input(-1.0, 110.0, 110.0, 0.0, 12))
        val result = BoostV1V5StatefulOfflineTimeline.inspect(listOf(bad))
        assertEquals(BoostV1V5StatefulOfflineTimeline.Finding.INVALID_V1, result.finding)
        assertFalse(result.canDeliverInsulin)
    }

    @Test fun twoCyclesShareOneV5StatefulReplayAndOneTimeline() {
        val result = BoostV1V5StatefulOfflineTimeline.inspect(listOf(frame(300_000), frame(600_000)))
        assertEquals(BoostV1V5StatefulOfflineTimeline.Finding.READY, result.finding)
        assertEquals(listOf(300_000L, 600_000L), result.entries.map { it.atMs })
        assertEquals(2, result.entries.size)
        assertFalse(result.canDeliverInsulin)
    }
}
