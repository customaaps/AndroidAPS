package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.boostG3HoldConditionsMet
import app.aaps.plugins.aps.openAPSBoost.boostPercentScaleReduction
import app.aaps.plugins.aps.openAPSBoost.boostSmbWithinMaxIob
import app.aaps.plugins.aps.openAPSBoost.boostSpikeOverrideAllowed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoostV1SafetyPortTest {
    @Test fun percentScaleAbove180NeverShrinksTheDivisor() {
        val pct = 2.0
        val slope = pct - 0.5
        for (bg in 181..400) {
            assertEquals(pct, pct - boostPercentScaleReduction(bg.toDouble(), slope), 1e-9)
        }
        assertEquals(0.75, pct - boostPercentScaleReduction(120.0, slope), 1e-9)
    }

    @Test fun conservativeNegativeSlopeIsPreserved() {
        val slope = -1.0
        assertEquals(-80.0 / 72.0, boostPercentScaleReduction(260.0, slope), 1e-9)
    }

    @Test fun smbCannotExceedMaxIobHeadroom() {
        assertEquals(1.0, boostSmbWithinMaxIob(2.5, 3.0, 2.0, 0.05), 1e-9)
        assertEquals(0.25, boostSmbWithinMaxIob(2.5, 3.0, 2.73, 0.05), 1e-9)
        assertEquals(0.2, boostSmbWithinMaxIob(2.5, 3.0, 2.73, 0.1), 1e-9)
        assertEquals(0.0, boostSmbWithinMaxIob(0.5, 3.0, 3.0, 0.05))
        assertEquals(0.0, boostSmbWithinMaxIob(0.5, Double.NaN, 1.0, 0.05))
    }

    @Test fun g3HoldRequiresAllThreeSignals() {
        assertTrue(boostG3HoldConditionsMet(0.0, 6.0, 4.0))
        assertFalse(boostG3HoldConditionsMet(1.0, 6.0, 4.0))
        assertFalse(boostG3HoldConditionsMet(0.0, 4.9, 4.0))
        assertFalse(boostG3HoldConditionsMet(0.0, 6.0, 2.9))
    }

    @Test fun spikeOverrideOnlyInPermittedTier() {
        assertTrue(boostSpikeOverrideAllowed(true, "REGULAR_OREF1", false, false))
        assertFalse(boostSpikeOverrideAllowed(false, "REGULAR_OREF1", false, false))
        assertFalse(boostSpikeOverrideAllowed(true, "REGULAR_OREF1", true, false))
        assertFalse(boostSpikeOverrideAllowed(true, "REGULAR_OREF1", false, true))
        for (tier in listOf("ACCELERATION", "ENHANCED_OREF1", "UAM_BOOST", "NONE", null)) {
            assertFalse(boostSpikeOverrideAllowed(true, tier, false, false))
        }
    }
}
