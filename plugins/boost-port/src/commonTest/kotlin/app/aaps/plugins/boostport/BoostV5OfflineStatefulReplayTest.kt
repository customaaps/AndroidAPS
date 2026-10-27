package app.aaps.plugins.boostport

import app.aaps.plugins.boostport.telemetry.BoostV5OfflineStatefulReplay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostV5OfflineStatefulReplayTest {
    @Test fun emptyAndOversizedReplaysAreBlockedWithoutInvokingEngine() {
        val empty = BoostV5OfflineStatefulReplay.inspect(emptyList())
        assertEquals(BoostV5OfflineStatefulReplay.Finding.EMPTY, empty.finding)
        assertFalse(empty.canDeliverInsulin)
    }
}
