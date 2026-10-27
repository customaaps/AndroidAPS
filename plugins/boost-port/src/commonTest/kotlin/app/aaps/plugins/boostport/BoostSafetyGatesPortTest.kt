package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoostV5.Phase3Inputs
import app.aaps.plugins.aps.openAPSBoostV5.applyPhase3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure regression cases; do not validate a live pump or complete dosing algorithm. */
class BoostSafetyGatesPortTest {
    private fun input() = Phase3Inputs(
        insulinToDeliver = 2.0,
        enableSmbPreChecks = true,
        minGuardBg = 140.0,
        minGuardThreshold = 90.0,
        maxDelta = 2.0,
        bg = 160.0,
        iob = 0.5,
        maxIob = 3.0,
        deltaAccl = 1.0,
        baseInsulinReq = 1.0,
        roundSmbTo = 0.05
    )

    @Test fun lowGuardGlucoseDisablesDose() {
        val r = applyPhase3(input().copy(minGuardBg = 85.0))
        assertEquals(0.0, r.finalDose)
        assertEquals("min_guard_bg", r.reductions.hardGateFired)
    }

    @Test fun excessiveDeltaDisablesDose() {
        val r = applyPhase3(input().copy(maxDelta = 50.0))
        assertEquals(0.0, r.finalDose)
        assertEquals("max_delta", r.reductions.hardGateFired)
    }

    @Test fun iobHeadroomCannotBeExceeded() {
        val r = applyPhase3(input().copy(insulinToDeliver = 5.0, iob = 2.8))
        assertTrue(r.reductions.maxIobClampApplied)
        assertTrue(r.finalDose >= 0.0)
        assertTrue(r.finalDose <= 0.2 + 1e-9)
    }

    @Test fun zeroHeadroomAllowsNoDose() {
        val r = applyPhase3(input().copy(iob = 3.0))
        assertEquals(0.0, r.finalDose)
    }

    @Test fun cgmQualityDampsWithoutAddingDose() {
        val normal = applyPhase3(input())
        val degraded = applyPhase3(input().copy(sensorQualityOk = false))
        assertTrue(degraded.finalDose <= normal.finalDose + 1e-9)
        assertEquals(0.7, degraded.reductions.sensorQualityCheck)
    }

    @Test fun decreasingGlucoseTrendDampsDose() {
        val accelerating = applyPhase3(input().copy(deltaAccl = 1.0, delta = 1.0))
        val decelerating = applyPhase3(input().copy(deltaAccl = -15.0, delta = 1.0))
        assertTrue(decelerating.finalDose <= accelerating.finalDose + 1e-9)
        assertEquals(0.3, decelerating.reductions.decelerationBrake)
    }

    @Test fun hardGateShortCircuitsProjectedRiskCallback() {
        var called = false
        val r = applyPhase3(input().copy(
            enableSmbPreChecks = false,
            mlHypoRisk = 0.1,
            riskAtProjectedIob = { called = true; 0.9 }
        ))
        assertEquals(0.0, r.finalDose)
        assertEquals(false, called)
    }
}
