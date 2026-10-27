package app.aaps.plugins.boostport.telemetry

/**
 * Read-only comparison of the two archived V6 metadata declarations.
 * The caller supplies parsed asset fields. No parsing, model inference,
 * APS registration, insulin calculation, preference or pump I/O occurs.
 */
internal object BoostMlMetadataPairAudit {
    enum class Finding {
        INVALID_RISK, INVALID_MEAL, COMMON_FEATURE_MISMATCH,
        INCONSISTENT_HORIZON, MATCH
    }

    data class Report(val finding: Finding, val firstMismatch: Int?)

    fun inspect(
        riskFeatures: List<String>,
        riskStatic: List<String>,
        riskWindowed: List<String>,
        riskLookback: Int,
        riskHorizonMin: Int,
        riskTrainingSamples: Int,
        riskTrainingUsers: Int,
        mealFeatures: List<String>,
        mealTrees: Int,
        mealDepth: Int,
        mealLeaves: Int,
        mealLookaheadMin: Int,
        mealAcceptanceGate: Double,
        mealStatus: String,
    ): Report {
        val risk = BoostRiskModelMetadataAudit.inspect(
            riskFeatures, riskStatic, riskWindowed, riskLookback,
            riskHorizonMin, riskTrainingSamples, riskTrainingUsers
        )
        if (risk.finding != BoostRiskModelMetadataAudit.Finding.MATCH)
            return Report(Finding.INVALID_RISK, risk.firstDifference)
        val meal = BoostMealModelMetadataAudit.inspect(
            mealFeatures, mealTrees, mealDepth, mealLeaves,
            mealLookaheadMin, mealAcceptanceGate, mealStatus
        )
        if (meal.finding != BoostMealModelMetadataAudit.Finding.MATCH)
            return Report(Finding.INVALID_MEAL, meal.firstFeatureMismatch)
        val shared = riskFeatures.take(mealFeatures.size)
        if (shared != mealFeatures) {
            val index = shared.indices.firstOrNull { shared[it] != mealFeatures[it] }
            return Report(Finding.COMMON_FEATURE_MISMATCH, index)
        }
        if (riskHorizonMin != mealLookaheadMin)
            return Report(Finding.INCONSISTENT_HORIZON, null)
        return Report(Finding.MATCH, null)
    }
}
