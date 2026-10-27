package app.aaps.plugins.boostport.telemetry

/**
 * Offline-only aggregation of historical V1 guard classifications and a
 * validated V1/V5 replay result. This is not a live APS or pump interface.
 */
internal object BoostV1V5DiagnosticCheckpoint {
    enum class Finding { INVALID_V1_GUARD, INVALID_V5_SESSION, REQUIRES_REVIEW, DIAGNOSTIC_ONLY }
    data class Input(
        val v1Guard: BoostV1LowGlucoseBranchAudit.Report,
        val v5Session: BoostV1V5DiagnosticResult.Report
    )
    data class Report(
        val finding: Finding,
        val lowGlucoseFlag: Boolean = false,
        val observationCount: Int = 0,
        val canDeliverInsulin: Boolean = false
    )
    fun inspect(input: Input): Report {
        if (input.v1Guard.finding == BoostV1LowGlucoseBranchAudit.Finding.INVALID)
            return Report(Finding.INVALID_V1_GUARD)
        if (input.v5Session.finding != BoostV1V5DiagnosticResult.Finding.DIAGNOSTIC_ONLY)
            return Report(Finding.INVALID_V5_SESSION)
        if (input.v1Guard.finding == BoostV1LowGlucoseBranchAudit.Finding.NAIVE_CRITICAL_LOW)
            return Report(Finding.REQUIRES_REVIEW, lowGlucoseFlag = true,
                observationCount = input.v5Session.entries.size)
        return Report(Finding.DIAGNOSTIC_ONLY,
            lowGlucoseFlag = input.v1Guard.finding == BoostV1LowGlucoseBranchAudit.Finding.LOW_EVENTUAL,
            observationCount = input.v5Session.entries.size)
    }
}
