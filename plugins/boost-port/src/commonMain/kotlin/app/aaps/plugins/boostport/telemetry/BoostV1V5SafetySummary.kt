package app.aaps.plugins.boostport.telemetry

/**
 * Aggregates historical protective findings for review in the offline Boost
 * diagnostic pipeline. Cannot supply a dose or an executable APS result.
 */
internal object BoostV1V5SafetySummary {
    enum class Finding { REJECTED, CRITICAL_LOW_REVIEW, LOW_GLUCOSE_REVIEW, DIAGNOSTIC_ONLY }
    data class Input(
        val checkpoint: BoostV1V5DiagnosticCheckpoint.Report,
        val cycle: BoostV1CycleProvenanceAudit.Report
    )
    data class Report(
        val finding: Finding,
        val observationCount: Int = 0,
        val canDeliverInsulin: Boolean = false
    )
    fun inspect(input: Input): Report {
        if (input.cycle.finding != BoostV1CycleProvenanceAudit.Finding.READY ||
            input.checkpoint.finding == BoostV1V5DiagnosticCheckpoint.Finding.INVALID_V1_GUARD ||
            input.checkpoint.finding == BoostV1V5DiagnosticCheckpoint.Finding.INVALID_V5_SESSION ||
            input.checkpoint.observationCount <= 0)
            return Report(Finding.REJECTED)
        return when {
            input.checkpoint.finding == BoostV1V5DiagnosticCheckpoint.Finding.REQUIRES_REVIEW ->
                Report(Finding.CRITICAL_LOW_REVIEW, input.checkpoint.observationCount)
            input.checkpoint.lowGlucoseFlag ->
                Report(Finding.LOW_GLUCOSE_REVIEW, input.checkpoint.observationCount)
            else -> Report(Finding.DIAGNOSTIC_ONLY, input.checkpoint.observationCount)
        }
    }
}
