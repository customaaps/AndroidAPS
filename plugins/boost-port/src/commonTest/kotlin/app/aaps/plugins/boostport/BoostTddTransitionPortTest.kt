package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.boostSwitchedToV6
import app.aaps.plugins.aps.openAPSBoost.boostTddJustEnabled
import app.aaps.plugins.aps.openAPSBoost.boostTddSettingsTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure KMP policy checks; these do not activate the Boost APS or change insulin delivery. */
class BoostTddTransitionPortTest {
    @Test fun tddOffRemovesTheSensitivityAdjustAndBgImpact() {
        val result = boostTddSettingsTransition(false, true, 100.0, false)
        assertFalse(result.adjustSens)
        assertEquals(0.0, result.velocityPct)
        assertTrue(result.clearedAdjustSens)
        assertTrue(result.zeroedVelocity)
    }

    @Test fun v6SwitchKeepsTddImpactWhileRemovingLegacyAdjust() {
        val result = boostTddSettingsTransition(true, true, 60.0, true)
        assertFalse(result.adjustSens)
        assertEquals(60.0, result.velocityPct)
        assertFalse(result.zeroedVelocity)
    }

    @Test fun tddEnableEdgeRestoresImpactWithoutEnablingAdjust() {
        val result = boostTddSettingsTransition(true, false, 0.0, false, tddJustEnabled = true)
        assertEquals(100.0, result.velocityPct)
        assertFalse(result.adjustSens)
        assertTrue(result.restoredVelocity)
        assertTrue(boostTddJustEnabled("false", true))
        assertFalse(boostTddJustEnabled("", true))
    }

    @Test fun v6TransitionIsDetectedOnce() {
        assertTrue(boostSwitchedToV6("v1", true))
        assertTrue(boostSwitchedToV6("", true))
        assertFalse(boostSwitchedToV6("v6", true))
        assertFalse(boostSwitchedToV6("v1", false))
    }
}
