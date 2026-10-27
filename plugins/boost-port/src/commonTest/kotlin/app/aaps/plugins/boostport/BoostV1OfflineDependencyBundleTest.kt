package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1OfflineCycleMath
import app.aaps.plugins.boostport.telemetry.BoostV1EngineInputContract
import app.aaps.plugins.boostport.telemetry.BoostV1OfflineDependencyBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostV1OfflineDependencyBundleTest {
    private fun input() = BoostV1OfflineDependencyBundle.Input(
        engine = BoostV1EngineInputContract.Inputs(
            nowMs = 1_000_000L,
            glucose = BoostV1EngineInputContract.Glucose(900_000L, 110.0, 0),
            profile = BoostV1EngineInputContract.Profile(1.0, 110.0, 2.0),
            iob = BoostV1EngineInputContract.Iob(0.1, 0.0),
            temp = BoostV1EngineInputContract.CurrentTemp(0.0, 0),
            meal = BoostV1EngineInputContract.Meal(0.0, 0.0),
            autosensRatio = 1.0
        ),
        scalar = BoostV1OfflineCycleMath.Input(110.0, 110.0, 110.0, 0.0, 12),
        comparisonTimeMs = 1_000_000L
    )

    @Test fun consistentSnapshotsRemainPassive() {
        val report = BoostV1OfflineDependencyBundle.inspect(input())
        assertEquals(BoostV1OfflineDependencyBundle.Finding.READY, report.finding)
        assertFalse(report.canDeliverInsulin)
    }

    @Test fun clockMismatchRejected() {
        val report = BoostV1OfflineDependencyBundle.inspect(input().copy(comparisonTimeMs = 1L))
        assertEquals(BoostV1OfflineDependencyBundle.Finding.MISMATCHED_SNAPSHOTS, report.finding)
    }
}
