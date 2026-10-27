package app.aaps.plugins.aps.openAPSBoost

/**
 * Fail-closed completeness check before comparing a V1 offline calculation
 * with an archived source RT. This does not synthesize missing treatment
 * decisions from partial diagnostics.
 */
internal object BoostV1RtComparisonReadiness {
    enum class Missing {
        SOURCE_RT, SOURCE_INPUT_IDENTITY, CANDIDATE_INPUT_IDENTITY,
        DIFFERENT_INPUTS, PARTIAL_CALCULATION, SOURCE_EQUIVALENT_ENGINE,
        ACTIONABLE_OUTPUT_FIELDS
    }
    data class Input(
        val offline: BoostV1OfflineRtEnvelope.Snapshot,
        val referenceRt: BoostV1ResultParity.Output?,
        val referenceInputDigest: String?,
        val candidateInputDigest: String?
    )
    data class Report(
        val missing: Set<Missing>,
        val readyForStrictParity: Boolean = missing.isEmpty(),
        val canDeliverInsulin: Boolean = false
    )
    fun inspect(input: Input): Report {
        val missing = buildSet {
            if (input.referenceRt == null) add(Missing.SOURCE_RT)
            if (input.referenceInputDigest.isNullOrBlank()) add(Missing.SOURCE_INPUT_IDENTITY)
            if (input.candidateInputDigest.isNullOrBlank()) add(Missing.CANDIDATE_INPUT_IDENTITY)
            if (!input.referenceInputDigest.isNullOrBlank() &&
                !input.candidateInputDigest.isNullOrBlank() &&
                input.referenceInputDigest != input.candidateInputDigest)
                add(Missing.DIFFERENT_INPUTS)
            if (input.offline.finding != BoostV1OfflineRtEnvelope.Finding.SNAPSHOT_READY)
                add(Missing.PARTIAL_CALCULATION)
            // Partial shadow results are not source-equivalent, even if values
            // happen to match one captured RT on a single cycle.
            if (!input.offline.sourceEquivalentRtAvailable)
                add(Missing.SOURCE_EQUIVALENT_ENGINE)
            // The offline envelope intentionally contains no dose or temp-basal
            // instructions and cannot be converted into an RT comparison output.
            add(Missing.ACTIONABLE_OUTPUT_FIELDS)
        }
        return Report(missing)
    }
}
