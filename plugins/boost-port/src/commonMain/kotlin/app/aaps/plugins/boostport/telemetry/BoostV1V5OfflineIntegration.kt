package app.aaps.plugins.boostport.telemetry

import app.aaps.plugins.aps.openAPSBoostV5.V5PersistedState

/**
 * Unified, read-only V1/V5 diagnostic run for historical replay.
 * Inputs remain explicitly caller supplied; this code is not registered as
 * an APS plugin and cannot create insulin/pump commands.
 */
internal object BoostV1V5OfflineIntegration {
    enum class Finding { INVALID_SESSION, INVALID_V1_PROVENANCE, INVALID_V1_GUARD, INVALID_V5_RESULT, READY }
    data class Input(
        val cycles: List<BoostV1V5GuardedSession.Cycle>,
        val lowGlucose: BoostV1LowGlucoseBranchAudit.Input,
        val v5InitialState: V5PersistedState = V5PersistedState()
    )
    data class Report(
        val finding: Finding,
        val reviewedCycles: Int = 0,
        val v1LowGlucose: BoostV1LowGlucoseBranchAudit.Finding? = null,
        val riskReviewRequired: Boolean = false,
        val canDeliverInsulin: Boolean = false
    )
    fun inspect(input: Input): Report {
        if (input.cycles.isEmpty() || input.cycles.size > 1000)
            return Report(Finding.INVALID_SESSION)
        for (cycle in input.cycles) {
            if (BoostV1CycleProvenanceAudit.inspect(cycle.v1).finding !=
                BoostV1CycleProvenanceAudit.Finding.READY)
                return Report(Finding.INVALID_V1_PROVENANCE)
        }
        // A standalone guard from another cycle/profile must never be silently
        // paired with the final frame of a replay.
        val last = input.cycles.last().v1
        if (input.lowGlucose != last.lowBranch ||
            input.lowGlucose.minTargetBg != last.minTargetMgDl)
            return Report(Finding.INVALID_V1_GUARD)
        val guard = BoostV1LowGlucoseBranchAudit.inspect(input.lowGlucose)
        if (guard.finding == BoostV1LowGlucoseBranchAudit.Finding.INVALID)
            return Report(Finding.INVALID_V1_GUARD)
        val replay = BoostV1V5GuardedSession.inspect(input.cycles, input.v5InitialState)
        val diagnostics = BoostV1V5DiagnosticResult.inspect(replay)
        if (diagnostics.finding != BoostV1V5DiagnosticResult.Finding.DIAGNOSTIC_ONLY)
            return Report(Finding.INVALID_V5_RESULT)
        val checkpoint = BoostV1V5DiagnosticCheckpoint.inspect(
            BoostV1V5DiagnosticCheckpoint.Input(guard, diagnostics)
        )
        val summary = BoostV1V5SafetySummary.inspect(
            BoostV1V5SafetySummary.Input(checkpoint,
                BoostV1CycleProvenanceAudit.Report(BoostV1CycleProvenanceAudit.Finding.READY))
        )
        if (summary.finding == BoostV1V5SafetySummary.Finding.REJECTED)
            return Report(Finding.INVALID_V5_RESULT)
        return Report(
            finding = Finding.READY,
            reviewedCycles = diagnostics.entries.size,
            v1LowGlucose = guard.finding,
            riskReviewRequired = summary.finding == BoostV1V5SafetySummary.Finding.CRITICAL_LOW_REVIEW ||
                summary.finding == BoostV1V5SafetySummary.Finding.LOW_GLUCOSE_REVIEW
        )
    }
}
