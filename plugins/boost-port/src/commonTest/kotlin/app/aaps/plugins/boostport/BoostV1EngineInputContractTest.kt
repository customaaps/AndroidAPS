package app.aaps.plugins.boostport

import app.aaps.plugins.boostport.telemetry.BoostV1EngineInputContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostV1EngineInputContractTest {
    private val c = BoostV1EngineInputContract
    private fun sample() = BoostV1EngineInputContract.Inputs(
        nowMs = 1_000_000L,
        glucose = BoostV1EngineInputContract.Glucose(900_000L, 110.0, 0),
        profile = BoostV1EngineInputContract.Profile(1.0, 110.0, 2.0),
        iob = BoostV1EngineInputContract.Iob(0.3, 0.01),
        temp = BoostV1EngineInputContract.CurrentTemp(0.0, 0),
        meal = BoostV1EngineInputContract.Meal(0.0, 0.0),
        autosensRatio = 1.0
    )

    @Test fun validSnapshotsAreOfflineOnly() {
        val result = c.inspect(sample())
        assertEquals(BoostV1EngineInputContract.Finding.READY_OFFLINE, result.finding)
        assertFalse(result.readyToCallRealEngine)
        assertFalse(result.canDeliverInsulin)
    }

    @Test fun staleAndCorruptedSensorsAreRejected() {
        assertEquals(BoostV1EngineInputContract.Finding.STALE_GLUCOSE,
            c.inspect(sample().copy(glucose = sample().glucose.copy(timestampMs = 100_000L))).finding)
        assertEquals(BoostV1EngineInputContract.Finding.INVALID_GLUCOSE,
            c.inspect(sample().copy(glucose = sample().glucose.copy(mgDl = 38.0))).finding)
    }
}
