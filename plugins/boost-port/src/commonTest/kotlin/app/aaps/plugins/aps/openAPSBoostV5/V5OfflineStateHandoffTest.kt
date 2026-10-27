package app.aaps.plugins.aps.openAPSBoostV5

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class V5OfflineStateHandoffTest {
    @Test fun absentSnapshotCannotRestore() {
        val result = V5OfflineStateHandoff.restore(null)
        assertEquals(V5OfflineStateHandoff.Finding.MISSING_SNAPSHOT, result.finding)
        assertNull(result.state)
        assertFalse(result.liveInvocationAllowed)
        assertFalse(result.insulinDeliveryAllowed)
    }

    @Test fun malformedSnapshotCannotRestoreOrUnlock() {
        val result = V5OfflineStateHandoff.restore(mapOf("unrecognized-schema" to "1"))
        assertEquals(V5OfflineStateHandoff.Finding.INVALID_SNAPSHOT, result.finding)
        assertNull(result.state)
        assertFalse(result.liveInvocationAllowed)
        assertFalse(result.insulinDeliveryAllowed)
    }

    @Test fun absentStateCannotBeExported() {
        assertNull(V5OfflineStateHandoff.export(null))
    }
}
