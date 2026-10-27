package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1OfflineCycleMath
import app.aaps.plugins.aps.openAPSBoostV5.V5Inputs
import app.aaps.plugins.aps.openAPSBoostV5.V5PersistedState
import app.aaps.plugins.boostport.telemetry.BoostV1V5OfflinePairedReplay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostV1V5OfflinePairedReplayTest {
    @Test fun clockMismatchBlocksBeforeInvokingEngine() {
        // No numerical treatment outputs are requested or returned.
        val v5 = V5Inputs(
            delta = 0.0, shortAvgDelta = 0.0, deltaAccl = 0.0, bg = 110.0,
            eventualBg = 110.0, targetBg = 110.0, maxDelta = 0.0,
            minGuardBg = 110.0, minGuardThreshold = 70.0, deltaHistory = listOf(0.0, 0.0, 0.0),
            iob = 0.0, maxIob = 1.0, baseInsulinReq = 0.0, roundSmbTo = 0.05,
            enableSmbPreChecks = false, mlHypoRisk = null, mlMealLikely = null,
            recentLowBg = 110.0, cumulativeRise30min = 0.0, hour = 12,
            exerciseActive = false, inPostExerciseWindow = false, nowMs = 9L
        )
        val report = BoostV1V5OfflinePairedReplay.inspect(
            BoostV1V5OfflinePairedReplay.Input(
                10L, BoostV1OfflineCycleMath.Input(110.0, 110.0, 110.0, 0.0, 12),
                v5, V5PersistedState()
            )
        )
        assertEquals(BoostV1V5OfflinePairedReplay.Finding.INVALID_CLOCK, report.finding)
        assertFalse(report.canDeliverInsulin)
    }
}
