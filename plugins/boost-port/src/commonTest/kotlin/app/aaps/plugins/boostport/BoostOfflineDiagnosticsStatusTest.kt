package app.aaps.plugins.boostport

import app.aaps.plugins.boostport.telemetry.BoostOfflineDiagnosticsStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class BoostOfflineDiagnosticsStatusTest {
    @Test fun unknownStatusFailsClosed() {
        assertEquals(BoostOfflineDiagnosticsStatus.NO_DATA,
            BoostOfflineDiagnosticsStatus.fromWire("ACTIVE_DOSING"))
        assertEquals(BoostOfflineDiagnosticsStatus.NO_DATA,
            BoostOfflineDiagnosticsStatus.fromWire(null))
    }
    @Test fun knownReviewFlagIsOnlyInformational() {
        assertEquals(BoostOfflineDiagnosticsStatus.REVIEW,
            BoostOfflineDiagnosticsStatus.fromWire("REVIEW"))
    }
}
