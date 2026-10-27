package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1PredictionArraysShadow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostV1PredictionArraysShadowTest {
    private fun input() = BoostV1PredictionArraysShadow.Input(
        bg = 110.0, sens = 50.0, ci = 0.0, uci = 0.0, cid = 0.0, acid = 0.0,
        remainingCATimeHours = 3.0, remainingCIpeak = 0.0, slopeFromDeviations = 0.0,
        insulinDivisor = 55.0, normalTarget = 110.0, sensNormalTarget = 50.0,
        velocity = 1.0, bgCap = 180.0,
        ticks = listOf(BoostV1PredictionArraysShadow.Tick(0.0, 0.0))
    )
    @Test fun oneNeutralTickKeepsAllFiveCurves() {
        val report = BoostV1PredictionArraysShadow.inspect(input())
        assertEquals(BoostV1PredictionArraysShadow.Finding.READY, report.finding)
        for (array in listOf(report.iob, report.cob, report.aCob, report.zt, report.uam))
            assertEquals(listOf(110, 110), array)
        assertFalse(report.canDeliverInsulin)
    }
    @Test fun rejectsMissingZeroTempTick() {
        val bad = input().copy(ticks = listOf(BoostV1PredictionArraysShadow.Tick(0.0, null)))
        assertEquals(BoostV1PredictionArraysShadow.Finding.INVALID_TICK,
            BoostV1PredictionArraysShadow.inspect(bad).finding)
    }
}
