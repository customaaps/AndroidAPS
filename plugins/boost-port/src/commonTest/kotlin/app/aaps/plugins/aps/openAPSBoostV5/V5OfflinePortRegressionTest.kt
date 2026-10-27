package app.aaps.plugins.aps.openAPSBoostV5

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Source-derived configuration bounds and disabled V5->V1 routing.
 * Pure JVM/common tests: no Preferences, plugin registration or pump access.
 */
class V5OfflinePortRegressionTest {
    @Test
    fun archivedAutoConfigKeysRetainSourceBounds() {
        val limits = listOf(
            Triple(BoostV5OfflineDoubleKey.ApsBoostBolus, 0.1, 10.0),
            Triple(BoostV5OfflineDoubleKey.ApsBoostMaxIob, 0.1, 12.0),
            Triple(BoostV5OfflineDoubleKey.ApsBoostV5Aggression, 0.7, 1.6),
            Triple(BoostV5OfflineDoubleKey.ApsBoostV5HypoCaution, 1.0, 2.0),
            Triple(BoostV5OfflineDoubleKey.ApsBoostCumulativeSmbCap60Min, 0.0, 10.0),
            Triple(BoostV5OfflineDoubleKey.ApsBoostV5ConfirmedCapU, 0.0, 7.5),
            Triple(BoostV5OfflineDoubleKey.ApsBoostV5CommittedCapU, 0.0, 2.5),
            Triple(BoostV5OfflineDoubleKey.ApsBoostV5PrimerCapU, 0.0, 2.5)
        )
        assertEquals(8, BoostV5OfflineDoubleKey.entries.size)
        for ((key, min, max) in limits) {
            assertEquals(min, key.min, key.name)
            assertEquals(max, key.max, key.name)
            assertTrue(key.defaultValue in key.min..key.max, key.name)
        }
    }

    @Test
    fun allOfflineRoutePrerequisiteCombinationsNeverInvokeOrDeliver() {
        for (bits in 0 until 16) {
            val v1 = (bits and 1) != 0
            val state = (bits and 2) != 0
            val v5 = (bits and 4) != 0
            val fallback = (bits and 8) != 0
            val report = V5V1DelegationOffline.inspect(
                V5V1DelegationOffline.Inputs(
                    v1EngineInputsAvailable = v1,
                    v5StateAvailable = state,
                    v5EngineAvailable = v5,
                    fallbackAvailable = fallback
                )
            )
            val expected = when {
                !v1 || !fallback -> V5V1DelegationOffline.Finding.V1_ENGINE_UNAVAILABLE
                !state || !v5 -> V5V1DelegationOffline.Finding.V5_DECISION_UNAVAILABLE
                else -> V5V1DelegationOffline.Finding.OFFLINE_ROUTE_DESCRIBED
            }
            assertEquals(expected, report.finding, "prerequisites=$bits")
            assertTrue(report.v1BaseEngineRequired)
            assertTrue(report.v5ActiveInOriginalPlugin)
            assertFalse(report.liveInvocationAllowed)
            assertFalse(report.insulinDeliveryAllowed)
        }
    }
}
