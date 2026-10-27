package app.aaps.plugins.boostport

import app.aaps.plugins.aps.openAPSBoost.BoostV1BasalLimitShadow
import app.aaps.plugins.boostport.telemetry.BoostV1V5OfflineIntegration
import app.aaps.plugins.boostport.telemetry.BoostV1V5SafetyMigrationGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoostV1V5SafetyMigrationGateTest {
    private val cap = BoostV1BasalLimitShadow.Input(
        maxBasal = 3.0, maxDailySafetyMultiplier = 3.0, maxDailyBasal = 1.0,
        currentBasalSafetyMultiplier = 4.0, currentBasal = 0.5
    )

    @Test fun readOnlyHistoricalCapIsNotACommand() {
        val report = BoostV1V5SafetyMigrationGate.inspect(
            BoostV1V5SafetyMigrationGate.Input(
                cap, BoostV1V5OfflineIntegration.Report(
                    BoostV1V5OfflineIntegration.Finding.READY, reviewedCycles = 2
                )
            )
        )
        assertEquals(BoostV1V5SafetyMigrationGate.Finding.DIAGNOSTIC_ONLY, report.finding)
        assertEquals(2.0, report.historicalMaxBasalCap)
        assertFalse(report.canDeliverInsulin)
    }

    @Test fun invalidReplayIsRejected() {
        val report = BoostV1V5SafetyMigrationGate.inspect(
            BoostV1V5SafetyMigrationGate.Input(
                cap, BoostV1V5OfflineIntegration.Report(
                    BoostV1V5OfflineIntegration.Finding.INVALID_V1_GUARD
                )
            )
        )
        assertEquals(BoostV1V5SafetyMigrationGate.Finding.INVALID_REPLAY, report.finding)
        assertFalse(report.canDeliverInsulin)
    }
}
