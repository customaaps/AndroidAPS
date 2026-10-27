package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoostV5.BoostV5AutoConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoostV5AutoConfigPortTest {
    private fun profile(days: Int = 14, readings: Int = 4000, low70: Double = 2.0, low54: Double = 0.5) =
        BoostV5AutoConfig.V1Profile(
            daysWithData = days,
            bgReadingCount = readings,
            tddMedianU = 30.0,
            manualBolusesU = listOf(2.0, 3.0, 4.0),
            smbAmountsU = listOf(0.1, 0.2, 0.3, 0.4),
            tbrBelow70Pct = low70,
            timeBelow54Pct = low54,
            meanGlucoseMgdl = 125.0,
            currentMaxIobU = 3.0,
            currentMaxBolusU = 5.0
        )

    @Test fun insufficientHistoryDoesNotProposeSettings() {
        assertNull(BoostV5AutoConfig.compute(profile(days = 6)))
        assertNull(BoostV5AutoConfig.compute(profile(readings = 1499)))
    }

    @Test fun conservativeProposalNeverRaisesAggressionAboveNeutral() {
        val nominal = assertNotNull(BoostV5AutoConfig.compute(profile()))
        val hypoProne = assertNotNull(BoostV5AutoConfig.compute(profile(low70 = 8.0, low54 = 2.0)))
        assertTrue(nominal.aggression <= 1.0)
        assertTrue(hypoProne.aggression < nominal.aggression)
        assertTrue(hypoProne.hypoCaution >= nominal.hypoCaution)
        assertFalse(hypoProne.aggressiveEarlyConfirm)
        assertFalse(hypoProne.velocityBudgetFloor)
    }

    @Test fun bolusAndIobBoundsAreCarriedFromProfile() {
        val result = assertNotNull(BoostV5AutoConfig.compute(profile()))
        assertEquals(3.0, result.maxIobU)
        assertEquals(5.0, result.bolusCapU)
        assertTrue(result.cumulativeSmbCap60MinU in 1.0..10.0)
    }

    @Test fun percentileIgnoresInvalidNonpositiveValues() {
        assertEquals(0.0, BoostV5AutoConfig.percentile(listOf(Double.NaN, -2.0, 0.0), 90.0))
        assertEquals(2.0, BoostV5AutoConfig.percentile(listOf(2.0), 90.0))
    }
}
