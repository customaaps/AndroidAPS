package app.aaps.plugins.boostport.telemetry

import app.aaps.plugins.aps.openAPSBoost.BoostV1BasalLimitShadow

/**
 * Read-only migration gate bringing V1 protective cap and V1/V5 replay into
 * one audit result. This is NOT an APSResult or a treatment recommendation.
 */
internal object BoostV1V5SafetyMigrationGate {
    enum class Finding { INVALID_V1_CAP, INVALID_REPLAY, REVIEW_REQUIRED, DIAGNOSTIC_ONLY }
    data class Input(
        val basalLimit: BoostV1BasalLimitShadow.Input,
        val replay: BoostV1V5OfflineIntegration.Report
    )
    data class Report(
        val finding: Finding,
        val historicalMaxBasalCap: Double? = null,
        val cyclesReviewed: Int = 0,
        val canDeliverInsulin: Boolean = false
    )
    fun inspect(i: Input): Report {
        val cap = BoostV1BasalLimitShadow.inspect(i.basalLimit)
        if (cap.finding != BoostV1BasalLimitShadow.Finding.READY ||
            cap.diagnosticMaxBasal == null)
            return Report(Finding.INVALID_V1_CAP)
        if (i.replay.finding != BoostV1V5OfflineIntegration.Finding.READY ||
            i.replay.reviewedCycles <= 0)
            return Report(Finding.INVALID_REPLAY)
        return Report(
            finding = if (i.replay.riskReviewRequired) Finding.REVIEW_REQUIRED
                else Finding.DIAGNOSTIC_ONLY,
            historicalMaxBasalCap = cap.diagnosticMaxBasal,
            cyclesReviewed = i.replay.reviewedCycles
        )
    }
}
