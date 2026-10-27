package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1BasalLimitShadow
import app.aaps.plugins.aps.openAPSBoost.BoostV1TempBasalShadow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostV1TempBasalShadowTest {
    private fun input() = BoostV1TempBasalShadow.Input(
        requestedRate = 3.0, requestedMinutes = 30, currentRate = 0.0,
        currentMinutes = 0, profileBasal = 1.0, skipNeutralTemps = true,
        limit = BoostV1BasalLimitShadow.Input(2.0, 3.0, 1.0, 2.0, 1.0)
    )
    @Test fun capsAndDescribesButNeverSchedulesTempBasal() {
        val result = BoostV1TempBasalShadow.inspect(input())
        assertEquals(BoostV1TempBasalShadow.Finding.WOULD_SET, result.finding)
        assertEquals(2.0, result.cappedRateForInspection)
        assertFalse(result.canDeliverInsulin)
    }
    @Test fun neutralTempIsDiagnosticOnly() {
        val result = BoostV1TempBasalShadow.inspect(
            input().copy(requestedRate = 1.0, currentMinutes = 30, currentRate = 0.0)
        )
        assertEquals(BoostV1TempBasalShadow.Finding.WOULD_CANCEL, result.finding)
    }
    @Test fun nonsensicalInputsFailClosed() {
        assertEquals(BoostV1TempBasalShadow.Finding.INVALID,
            BoostV1TempBasalShadow.inspect(input().copy(requestedRate = Double.NaN)).finding)
    }
}
