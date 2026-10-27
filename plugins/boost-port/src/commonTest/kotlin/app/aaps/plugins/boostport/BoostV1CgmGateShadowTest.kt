package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1CgmGateShadow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostV1CgmGateShadowTest {
    private fun input() = BoostV1CgmGateShadow.Input(1_000_000L, 940_000L, 110.0, 0, false)
    @Test fun validGlucoseIsDiagnosticOnly() {
        val r = BoostV1CgmGateShadow.inspect(input())
        assertEquals(BoostV1CgmGateShadow.Finding.USABLE, r.finding)
        assertFalse(r.wouldEnterV1EarlyExit)
        assertFalse(r.canDeliverInsulin)
    }
    @Test fun flatAndNoisySamplesAreDetected() {
        assertEquals(BoostV1CgmGateShadow.Finding.FLAT,
            BoostV1CgmGateShadow.inspect(input().copy(flatBGsDetected = true)).finding)
        assertEquals(BoostV1CgmGateShadow.Finding.CGM_UNRELIABLE,
            BoostV1CgmGateShadow.inspect(input().copy(noise = 3)).finding)
    }
    @Test fun oldAndFutureSamplesAreDetected() {
        assertEquals(BoostV1CgmGateShadow.Finding.STALE,
            BoostV1CgmGateShadow.inspect(input().copy(glucoseTimeMs = 100_000L)).finding)
        assertEquals(BoostV1CgmGateShadow.Finding.FUTURE,
            BoostV1CgmGateShadow.inspect(input().copy(glucoseTimeMs = 1_400_000L)).finding)
    }
}
