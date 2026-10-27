package app.aaps.plugins.aps.openAPSBoostV5

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class V5OfflineAdapterGateTest {
    @Test fun unavailableBaseEngineFailsClosed() {
        val result = V5OfflineAdapterGate.inspect(
            v1EngineInputsAvailable = false,
            fallbackAvailable = true,
            v5EngineAvailable = true,
            state = null
        )
        assertEquals(V5OfflineAdapterGate.Finding.MISSING_V1_INPUT, result.finding)
        assertNull(result.snapshotFields)
        assertFalse(result.liveInvocationAllowed)
        assertFalse(result.insulinDeliveryAllowed)
    }

    @Test fun absentFallbackFailsClosed() {
        val result = V5OfflineAdapterGate.inspect(true, false, true, null)
        assertEquals(V5OfflineAdapterGate.Finding.MISSING_V1_INPUT, result.finding)
        assertFalse(result.insulinDeliveryAllowed)
    }

    @Test fun missingPersistedStateFailsClosed() {
        val result = V5OfflineAdapterGate.inspect(true, true, true, null)
        assertEquals(V5OfflineAdapterGate.Finding.MISSING_V5_STATE, result.finding)
        assertNull(result.snapshotFields)
        assertFalse(result.liveInvocationAllowed)
    }

    @Test fun missingV5EngineFailsClosed() {
        val result = V5OfflineAdapterGate.inspect(true, true, false, null)
        assertEquals(V5OfflineAdapterGate.Finding.MISSING_V5_STATE, result.finding)
        assertFalse(result.insulinDeliveryAllowed)
    }
}
