package app.aaps.plugins.boostport.telemetry

/** Diagnostic-only composition of the V1 forecast and low-glucose guard. */
internal object BoostV1GuardedForecastReview {
    enum class Finding { INVALID_CONTEXT, FORECAST_REJECTED, GUARD_REJECTED, DIAGNOSTIC_ONLY }
    data class Input(
        val result: BoostV1OfflineGuardedResult.Input,
        val low: BoostV1LowGlucoseBranchAudit.Input
    )
    data class Report(
        val finding: Finding,
        val lowBranch: BoostV1LowGlucoseBranchAudit.Finding? = null,
        val canDeliverInsulin: Boolean = false
    )
    fun inspect(input: Input): Report {
        if (input.result.clockMs <= 0L) return Report(Finding.INVALID_CONTEXT)
        val guarded = BoostV1OfflineGuardedResult.inspect(input.result)
        if (guarded.finding != BoostV1OfflineGuardedResult.Finding.DIAGNOSTIC_ONLY)
            return Report(Finding.FORECAST_REJECTED)
        val low = BoostV1LowGlucoseBranchAudit.inspect(input.low)
        return if (low.finding == BoostV1LowGlucoseBranchAudit.Finding.INVALID)
            Report(Finding.GUARD_REJECTED)
        else Report(Finding.DIAGNOSTIC_ONLY, low.finding)
    }
}
