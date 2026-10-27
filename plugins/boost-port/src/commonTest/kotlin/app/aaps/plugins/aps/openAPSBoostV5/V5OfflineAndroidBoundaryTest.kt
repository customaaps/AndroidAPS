package app.aaps.plugins.aps.openAPSBoostV5

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class V5OfflineAndroidBoundaryTest {
    @Test fun absentSnapshotCannotEnterRoute() {
        val report = V5OfflineAndroidBoundary.inspect(null, true, true, true)
        assertEquals(V5OfflineAndroidBoundary.Finding.MISSING_STATE, report.finding)
        assertNull(report.savedFields)
        assertFalse(report.liveInvocationAllowed)
        assertFalse(report.insulinDeliveryAllowed)
    }

    @Test fun malformedSnapshotCannotEnterRoute() {
        val report = V5OfflineAndroidBoundary.inspect(
            mapOf("unexpected" to "value"), true, true, true
        )
        assertEquals(V5OfflineAndroidBoundary.Finding.INVALID_STATE, report.finding)
        assertNull(report.savedFields)
        assertFalse(report.insulinDeliveryAllowed)
    }

    @Test fun malformedSnapshotWinsOverMissingV1Inputs() {
        val report = V5OfflineAndroidBoundary.inspect(
            mapOf("unexpected" to "value"), false, false, false
        )
        assertEquals(V5OfflineAndroidBoundary.Finding.INVALID_STATE, report.finding)
        assertFalse(report.liveInvocationAllowed)
    }
}
