package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1CgmGateShadow
import app.aaps.plugins.boostport.telemetry.BoostV1ForecastPipeline
import app.aaps.plugins.boostport.telemetry.BoostV1OfflineGuardedResult
import app.aaps.plugins.boostport.telemetry.BoostV1OfflineResultEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class BoostV1OfflineGuardedResultTest {
    @Test fun rejectedCgmDoesNotExposeAnyForecast() {
        val input = BoostV1OfflineGuardedResult.Input(
            clockMs = 1_000_000L,
            cgm = BoostV1CgmGateShadow.Input(1_000_000L, 940_000L, 38.0, 3, false),
            result = BoostV1OfflineResultEnvelope.Input(
                1_000_000L, BoostV1ForecastPipeline.Report(BoostV1ForecastPipeline.Finding.INVALID_MEAL)
            )
        )
        val report = BoostV1OfflineGuardedResult.inspect(input)
        assertEquals(BoostV1OfflineGuardedResult.Finding.SENSOR_REJECTED, report.finding)
        assertNull(report.result)
        assertFalse(report.canDeliverInsulin)
    }
    @Test fun mismatchedClocksFailBeforeSensorProcessing() {
        val input = BoostV1OfflineGuardedResult.Input(
            1_000_000L,
            BoostV1CgmGateShadow.Input(1_000_000L, 940_000L, 110.0, 0, false),
            BoostV1OfflineResultEnvelope.Input(
                2_000_000L, BoostV1ForecastPipeline.Report(BoostV1ForecastPipeline.Finding.INVALID_MEAL)
            )
        )
        assertEquals(BoostV1OfflineGuardedResult.Finding.INVALID_CONTEXT,
            BoostV1OfflineGuardedResult.inspect(input).finding)
    }
}
