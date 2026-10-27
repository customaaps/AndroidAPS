package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.NightWindow
import app.aaps.plugins.aps.openAPSBoostV5.ConfirmTrancheController
import app.aaps.plugins.aps.openAPSBoostV5.Phase3Inputs
import app.aaps.plugins.aps.openAPSBoostV5.applyPhase3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * These verify the migrated pure source code, not the unported APS/pump adapter.
 * A green result does not certify dosing compatibility.
 */
class BoostPortMigrationTest {
    @Test
    fun disabledByDefault() {
        assertFalse(BoostPortKey.AuditRequested.defaultValue)
    }

    @Test
    fun clockWindowHandlesMidnightWithoutChangingDosingPolicy() {
        val hour = 3_600_000L
        assertTrue(NightWindow.contains(23 * hour, 22 * hour, 7 * hour))
        assertTrue(NightWindow.contains(3 * hour, 22 * hour, 7 * hour))
        assertFalse(NightWindow.contains(8 * hour, 22 * hour, 7 * hour))
        assertFalse(NightWindow.contains(12 * hour, 12 * hour, 12 * hour))
    }

    @Test
    fun hardGateZeroesTheUncommittedBoostDose() {
        val r = applyPhase3(
            Phase3Inputs(
                insulinToDeliver = 1.0,
                enableSmbPreChecks = false,
                minGuardBg = 140.0,
                minGuardThreshold = 90.0,
                maxDelta = 4.0,
                bg = 160.0,
                iob = 0.5,
                maxIob = 3.0,
                deltaAccl = 1.0,
                baseInsulinReq = 1.0,
                roundSmbTo = 0.05
            )
        )
        assertEquals(0.0, r.finalDose)
        assertEquals("enable_smb_pre_checks", r.reductions.hardGateFired)
    }

    @Test
    fun droppedMealStateNeverReleasesPendingTranche() {
        val t = ConfirmTrancheController()
        assertEquals(1.0, t.onConfirm(1_000_000L, 160.0, 2.0))
        assertTrue(t.heldU() > 0.0)
        val r = t.onCycleBounded(
            1_600_000L, 180.0,
            ConfirmTrancheController.ReleaseBounds(
                inMealState = false,
                hardGateFired = false,
                postRescueWindow = false,
                ceilingU = 10.0
            )
        )
        assertEquals(0.0, r.units)
        assertEquals(0.0, t.heldU())
    }
}
