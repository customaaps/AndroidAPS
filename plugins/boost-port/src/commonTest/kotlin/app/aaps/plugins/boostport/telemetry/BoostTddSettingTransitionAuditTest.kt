package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoostTddSettingTransitionAuditTest {
    private val audit = BoostTddSettingTransitionAudit

    private fun inspect(
        useTdd: Boolean = true,
        adjustSens: Boolean = false,
        velocityPct: Double = 100.0,
        switchedToV6: Boolean = false,
        justEnabled: Boolean = false,
        onPct: Double = 100.0,
    ) = audit.inspect(
        BoostTddSettingTransitionAudit.Input(
            useTdd = useTdd,
            adjustSens = adjustSens,
            velocityPct = velocityPct,
            switchedToV6 = switchedToV6,
            tddJustEnabled = justEnabled,
            velocityOnPct = onPct,
        )
    )

    @Test fun disablingTddWouldClearSensitivityAndZeroVelocity() {
        val r = inspect(useTdd = false, adjustSens = true)!!
        assertFalse(r.wouldAdjustSens)
        assertEquals(0.0, r.wouldVelocityPct)
        assertTrue(r.wouldClearAdjustSens)
        assertTrue(r.wouldZeroVelocity)
        assertTrue(r.wouldChange)
    }

    @Test fun transitionToV6WouldClearAdjustmentButKeepTddVelocity() {
        val r = inspect(adjustSens = true, velocityPct = 40.0, switchedToV6 = true)!!
        assertFalse(r.wouldAdjustSens)
        assertEquals(40.0, r.wouldVelocityPct)
        assertTrue(r.wouldClearAdjustSens)
        assertFalse(r.wouldZeroVelocity)
        assertFalse(r.wouldRestoreVelocity)
    }

    @Test fun newlyEnabledTddWouldRestoreFactoryImpact() {
        val r = inspect(velocityPct = 0.0, justEnabled = true)!!
        assertEquals(100.0, r.wouldVelocityPct)
        assertTrue(r.wouldRestoreVelocity)
        assertTrue(r.wouldChange)
    }

    @Test fun configuredOnImpactIsRespectedOnlyAtEnableEdge() {
        val restored = inspect(velocityPct = 0.0, justEnabled = true,
            onPct = 75.0)!!
        assertEquals(75.0, restored.wouldVelocityPct)
        val unchanged = inspect(velocityPct = 0.0, justEnabled = false,
            onPct = 75.0)!!
        assertEquals(0.0, unchanged.wouldVelocityPct)
        assertFalse(unchanged.wouldChange)
    }

    @Test fun ordinaryEnabledCyclePreservesExistingImpactAndAdjustment() {
        val r = inspect(adjustSens = true, velocityPct = 60.0)!!
        assertTrue(r.wouldAdjustSens)
        assertEquals(60.0, r.wouldVelocityPct)
        assertFalse(r.wouldChange)
    }

    @Test fun disabledTddWithAlreadySafeSettingsDoesNotProposeChange() {
        val r = inspect(useTdd = false, velocityPct = 0.0)!!
        assertFalse(r.wouldAdjustSens)
        assertEquals(0.0, r.wouldVelocityPct)
        assertFalse(r.wouldChange)
    }

    @Test fun enablingTddNeverReEnablesUserDisabledAdjustment() {
        val r = inspect(adjustSens = false, velocityPct = 0.0,
            justEnabled = true)!!
        assertFalse(r.wouldAdjustSens)
        assertTrue(r.wouldRestoreVelocity)
    }

    @Test fun onlyRecordedFalseToTrueCountsAsTddEnableEdge() {
        assertTrue(audit.tddJustEnabled("false", true))
        assertFalse(audit.tddJustEnabled("", true))
        assertFalse(audit.tddJustEnabled("true", true))
        assertFalse(audit.tddJustEnabled("false", false))
        assertFalse(audit.tddJustEnabled("False", true))
    }

    @Test fun freshInstallCountsAsFirstV6CycleWhenV6Active() {
        assertTrue(audit.switchedToV6("", true))
        assertTrue(audit.switchedToV6("v1", true))
        assertFalse(audit.switchedToV6("v6", true))
        assertFalse(audit.switchedToV6("v1", false))
        assertFalse(audit.switchedToV6("v6", false))
    }

    @Test fun whenTddIsNotJustEnabledVelocityCannotIncrease() {
        for (useTdd in listOf(true, false))
            for (adjustSens in listOf(true, false))
                for (v6 in listOf(true, false))
                    for (velocity in listOf(0.0, 50.0, 100.0)) {
                        val r = inspect(useTdd, adjustSens, velocity, v6)!!
                        assertTrue(r.wouldVelocityPct <= velocity)
                        if (!adjustSens) assertFalse(r.wouldAdjustSens)
                    }
    }

    @Test fun disabledTddOverridesEvenContradictoryEnableEdge() {
        val r = inspect(useTdd = false, adjustSens = true,
            velocityPct = 60.0, justEnabled = true)!!
        assertEquals(0.0, r.wouldVelocityPct)
        assertFalse(r.wouldRestoreVelocity)
    }

    @Test fun invalidPercentageAbstainsWithoutProposingPreferencesWrites() {
        assertNull(inspect(velocityPct = Double.NaN))
        assertNull(inspect(velocityPct = Double.POSITIVE_INFINITY))
        assertNull(inspect(velocityPct = -1.0))
        assertNull(inspect(onPct = -1.0))
        assertNull(inspect(onPct = Double.NaN))
    }

    @Test fun enableEdgeWithAlreadyConfiguredPercentageIsNoOp() {
        val r = inspect(velocityPct = 100.0, justEnabled = true)!!
        assertFalse(r.wouldRestoreVelocity)
        assertFalse(r.wouldChange)
    }
}
