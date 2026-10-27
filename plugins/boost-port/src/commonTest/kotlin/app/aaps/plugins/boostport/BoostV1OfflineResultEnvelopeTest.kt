package app.aaps.plugins.boostport

import app.aaps.plugins.boostport.telemetry.BoostV1OfflineResultEnvelope
import app.aaps.plugins.boostport.telemetry.BoostV1ForecastPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostV1OfflineResultEnvelopeTest {
    @Test fun rejectedForecastCannotBecomeAnApsAction() {
        val rejected = BoostV1OfflineResultEnvelope.inspect(
            BoostV1OfflineResultEnvelope.Input(
                300000L, BoostV1ForecastPipeline.Report(BoostV1ForecastPipeline.Finding.INVALID_MEAL)
            )
        )
        assertEquals(BoostV1OfflineResultEnvelope.Finding.INVALID_FORECAST, rejected.finding)
        assertFalse(rejected.canDeliverInsulin)
        assertEquals(emptyList(), rejected.predictedIobMgDl)
    }
}
