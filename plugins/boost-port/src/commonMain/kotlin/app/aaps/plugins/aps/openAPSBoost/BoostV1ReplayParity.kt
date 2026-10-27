package app.aaps.plugins.aps.openAPSBoost

/**
 * Offline batch parity verification. Inputs must be independently captured
 * from reference and candidate executions on the same stored glucose/IOB/profile
 * samples; this never executes dosing logic.
 */
internal object BoostV1ReplayParity {
    enum class Scenario { HYPOGLYCEMIA, MAX_IOB, SMB, UNRELIABLE_CGM, NORMAL }
    data class Pair(
        val recordingId: String,
        val referenceInputFingerprint: String,
        val candidateInputFingerprint: String,
        val scenario: Scenario,
        val reference: BoostV1ResultParity.Output,
        val candidate: BoostV1ResultParity.Output
    )
    data class Mismatch(
        val recordingId: String,
        val scenario: Scenario,
        val fields: Set<BoostV1ResultParity.Field>,
        val comparable: Boolean
    )
    data class Summary(
        val total: Int,
        val mismatches: List<Mismatch>,
        val missingScenarios: Set<Scenario>,
        val duplicateIds: Set<String>,
        val valid: Boolean,
        val eligibleForPumpDispatch: Boolean = false
    )

    fun verify(pairs: List<Pair>, required: Set<Scenario> = Scenario.entries.toSet()): Summary {
        val duplicates = pairs.groupBy { it.recordingId }
            .filterValues { it.size != 1 }
            .keys
        val missing = required - pairs.map { it.scenario }.toSet()
        val diffs = pairs.mapNotNull { pair ->
            val report = BoostV1ResultParity.compare(pair.reference, pair.candidate)
            val fingerprintValid = pair.recordingId.isNotBlank() &&
                pair.referenceInputFingerprint.isNotBlank() &&
                pair.referenceInputFingerprint == pair.candidateInputFingerprint
            if (report.equivalent && fingerprintValid) null
            else Mismatch(pair.recordingId, pair.scenario, report.mismatches, report.comparable && fingerprintValid)
        }
        return Summary(
            total = pairs.size,
            mismatches = diffs,
            missingScenarios = missing,
            duplicateIds = duplicates,
            valid = pairs.isNotEmpty() && diffs.isEmpty() && missing.isEmpty() && duplicates.isEmpty()
        )
    }
}
