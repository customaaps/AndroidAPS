package app.aaps.plugins.aps.openAPSBoostV5

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class V5V1DelegationOfflineTest {
    @Test fun missingV1EngineBlocksRoute() {
        val report = V5V1DelegationOffline.inspect(
            V5V1DelegationOffline.Inputs(false, true, true, true)
        )
        assertEquals(V5V1DelegationOffline.Finding.V1_ENGINE_UNAVAILABLE, report.finding)
        assertFalse(report.liveInvocationAllowed)
    }

    @Test fun missingV5StateBlocksRoute() {
        val report = V5V1DelegationOffline.inspect(
            V5V1DelegationOffline.Inputs(true, false, true, true)
        )
        assertEquals(V5V1DelegationOffline.Finding.V5_DECISION_UNAVAILABLE, report.finding)
        assertFalse(report.insulinDeliveryAllowed)
    }

    @Test fun completeOfflineRouteCannotDeliverInsulin() {
        val report = V5V1DelegationOffline.inspect(
            V5V1DelegationOffline.Inputs(true, true, true, true)
        )
        assertEquals(V5V1DelegationOffline.Finding.OFFLINE_ROUTE_DESCRIBED, report.finding)
        assertTrue(report.v1BaseEngineRequired)
        assertTrue(report.v5ActiveInOriginalPlugin)
        assertFalse(report.liveInvocationAllowed)
        assertFalse(report.insulinDeliveryAllowed)
    }
    @Test fun everyMissingDependencyRemainsOfflineAndUndeliverable() {
        for (engine in listOf(false, true)) for (state in listOf(false, true)) {
            for (decision in listOf(false, true)) for (fallback in listOf(false, true)) {
                val report = V5V1DelegationOffline.inspect(
                    V5V1DelegationOffline.Inputs(engine, state, decision, fallback)
                )
                val expected = when {
                    !engine || !fallback -> V5V1DelegationOffline.Finding.V1_ENGINE_UNAVAILABLE
                    !state || !decision -> V5V1DelegationOffline.Finding.V5_DECISION_UNAVAILABLE
                    else -> V5V1DelegationOffline.Finding.OFFLINE_ROUTE_DESCRIBED
                }
                assertEquals(expected, report.finding)
                assertFalse(report.liveInvocationAllowed)
                assertFalse(report.insulinDeliveryAllowed)
            }
        }
    }

}
