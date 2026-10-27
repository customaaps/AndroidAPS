package app.aaps.plugins.boostport

import app.aaps.plugins.boostport.telemetry.BoostV1V5GuardedSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostV1V5GuardedSessionTest {
    @Test fun emptySessionCannotDeliver() {
        val r = BoostV1V5GuardedSession.inspect(emptyList())
        assertEquals(BoostV1V5GuardedSession.Finding.EMPTY, r.finding)
        assertFalse(r.canDeliverInsulin)
    }
}
