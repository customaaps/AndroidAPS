package app.aaps.plugins.boostport

import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Source BoostV1SafetyEngineTest cases, now executing the complete portable V1 engine. */
class BoostV1FullReplaySafetyTest {
    @Test fun tierThreeAbove180PreservesInsulinReqPercentage() {
        val c = V1ReplayFixture.cycle(270.0, 12.0, 8.0, 4.0, 0.5)
        val profile = V1ReplayFixture.profile(270.0, boostBolus = 10.0)
        val r = V1ReplayFixture.run(c.copy(profile = profile))
        assertEquals("UAM_BOOST", r.boostTier, r.reason.toString())
        assertTrue(r.insulinReq!! > 0.0)
        assertEquals(floor(minOf(r.insulinReq!! / (100.0 / 85.0), 10.0) * 20) / 20,
            r.units ?: 0.0, 1e-9)
    }

    @Test fun finalSmbStaysInsideMaxIob() {
        val c = V1ReplayFixture.cycle(105.0, 9.0, 6.0, 3.0, 1.0)
        val profile = V1ReplayFixture.profile(105.0, maxIob = 1.5)
        val changed: V1ReplayFixture.Cycle = c.copy(profile = profile)
        val r = V1ReplayFixture.run(changed)
        assertEquals("UAM_BOOST", r.boostTier)
        assertTrue((r.units ?: 0.0) in 0.000001..0.500000001)
    }

    @Test fun recentLowKeepsG3Hold() {
        val c = V1ReplayFixture.cycle()
        val r = V1ReplayFixture.run(c.copy(profile = V1ReplayFixture.profile(130.0, recentLowBG = 62.0)))
        assertEquals("NONE", r.boostTier)
        assertEquals(0.0, r.units ?: 0.0)
    }

    @Test fun ordinaryRiseAlsoKeepsG3Hold() {
        val r = V1ReplayFixture.run(V1ReplayFixture.cycle())
        assertEquals("NONE", r.boostTier)
        assertEquals(0.0, r.units ?: 0.0)
    }

    @Test fun postRescueWindowBlocksSpikeOverride() {
        val c = V1ReplayFixture.cycle(200.0, 6.0, 6.5, 5.0, 0.3)
        val r = V1ReplayFixture.run(c.copy(
            profile = V1ReplayFixture.profile(200.0, recentLowBG = 70.0), recentLow45 = 70.0
        ))
        assertEquals("REGULAR_OREF1", r.boostTier)
        assertTrue(r.insulinReq!! > 1.5)
        assertTrue((r.units ?: 0.0) <= 0.500000001)
    }

    @Test fun spikeOverrideStillWorksOutsidePostRescueWindow() {
        val r = V1ReplayFixture.run(V1ReplayFixture.cycle(200.0, 6.0, 6.5, 5.0, 0.3))
        assertEquals("REGULAR_OREF1", r.boostTier)
        assertTrue((r.units ?: 0.0) > 0.5)
    }
}
