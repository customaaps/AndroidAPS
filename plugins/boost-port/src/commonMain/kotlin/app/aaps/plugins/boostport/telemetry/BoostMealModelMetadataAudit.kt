package app.aaps.plugins.boostport.telemetry

/**
 * Offline, non-actuating inspection of the V6 meal-likelihood model metadata.
 * Caller provides parsed metadata; this object neither loads a model nor
 * performs predictions, dose calculations, APS registration or pump control.
 */
internal object BoostMealModelMetadataAudit {
    enum class Finding {
        FEATURE_MISMATCH, INVALID_TREE_COUNT, INVALID_DEPTH,
        INVALID_LEAVES, INVALID_HORIZON, INVALID_ACCEPTANCE_GATE,
        INVALID_MODEL_STATUS, MATCH
    }

    data class Report(val finding: Finding, val firstFeatureMismatch: Int?)

    fun inspect(
        featureNames: List<String>,
        treeCount: Int,
        maxDepth: Int,
        leafCount: Int,
        lookaheadMinutes: Int,
        acceptanceGate: Double,
        status: String,
    ): Report {
        fun fail(f: Finding, index: Int? = null) = Report(f, index)
        val expected = BoostMlAssetSchemaAudit.BASE_FEATURES
        if (featureNames != expected) {
            val index = (0 until minOf(featureNames.size, expected.size))
                .firstOrNull { featureNames[it] != expected[it] }
                ?: minOf(featureNames.size, expected.size)
            return fail(Finding.FEATURE_MISMATCH, index)
        }
        if (treeCount != BoostMlAssetSchemaAudit.requiredTreeCount(
                BoostMlAssetSchemaAudit.Kind.MEAL_LIKELIHOOD
            )) return fail(Finding.INVALID_TREE_COUNT)
        if (maxDepth != 4) return fail(Finding.INVALID_DEPTH)
        if (leafCount != 15) return fail(Finding.INVALID_LEAVES)
        if (lookaheadMinutes != 90) return fail(Finding.INVALID_HORIZON)
        if (!acceptanceGate.isFinite() || acceptanceGate != 0.65)
            return fail(Finding.INVALID_ACCEPTANCE_GATE)
        if (status != "ACCEPTED") return fail(Finding.INVALID_MODEL_STATUS)
        return Report(Finding.MATCH, null)
    }
}
