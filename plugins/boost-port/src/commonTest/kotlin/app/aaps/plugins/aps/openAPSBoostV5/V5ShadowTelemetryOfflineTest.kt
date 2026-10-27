package app.aaps.plugins.aps.openAPSBoostV5

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class V5ShadowTelemetryOfflineTest {
    @Test fun offlineTelemetryHasNoActiveDosingFlag() {
        val record = V5ShadowTelemetryOffline.Cycle(
            score = 0.6,
            state = "OBSERVING",
            age = 2,
            budget = 0.8,
            actionMultiplier = 1.0,
            finalDoseU = 0.2,
            velocityFactor = 1.0,
            doseAfterCapsU = 0.3,
            doseAfterBrakesU = 0.2,
            confirmGate = "n/a",
            prospectiveConfirmShotU = 0.0,
            floorWouldAddU = null,
            velocityBudgetWouldAddU = null,
            mealSessionStarted = false,
        )
        assertFalse(record.activeDosingEnabled)
        assertEquals(0.3, record.doseAfterCapsU)
        assertEquals(0.2, record.doseAfterBrakesU)
    }
}
