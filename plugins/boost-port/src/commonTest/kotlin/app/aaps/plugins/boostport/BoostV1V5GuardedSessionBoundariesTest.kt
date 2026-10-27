package app.aaps.plugins.boostport

import app.aaps.plugins.boostport.telemetry.BoostV1V5GuardedSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoostV1V5GuardedSessionBoundariesTest {
    @Test fun emptySessionNeverOffersDosing() {
        val result = BoostV1V5GuardedSession.inspect(emptyList())
        assertEquals(BoostV1V5GuardedSession.Finding.EMPTY, result.finding)
        assertTrue(result.observations.isEmpty())
        assertFalse(result.canDeliverInsulin)
    }

    @Test fun diagnosticResultDefaultsToNoDelivery() {
        val result = BoostV1V5GuardedSession.Report(
            BoostV1V5GuardedSession.Finding.INVALID_V1,
            failedIndex = 0
        )
        assertEquals(0, result.failedIndex)
        assertFalse(result.canDeliverInsulin)
    }
}
