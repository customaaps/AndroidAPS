package app.aaps.plugins.boostport.telemetry

/**
 * Offline cross-check of both inactive Boost V6 LightGBM asset declarations.
 *
 * Caller supplies parsed model headers and the model's declared feature names;
 * no model is loaded or evaluated, and no live CGM, APS, dose, pump, settings
 * or persistence boundary is crossed. A positive result is schema agreement,
 * not evidence of model accuracy or clinical safety.
 */
internal object BoostMlDualModelConsistencyAudit {
    enum class Finding {
        INVALID_RISK_HEADER, INVALID_MEAL_HEADER,
        INVALID_RISK_FEATURE_ORDER, INVALID_MEAL_FEATURE_ORDER,
        SHARED_BASE_MISMATCH, MATCH
    }

    data class Report(val finding: Finding, val firstDifference: Int?)

    fun inspect(
        riskHeader: BoostMlAssetHeaderAudit.Header,
        mealHeader: BoostMlAssetHeaderAudit.Header,
        riskFeatureNames: List<String>,
        mealFeatureNames: List<String>,
    ): Report {
        fun fail(f: Finding, at: Int? = null) = Report(f, at)
        if (BoostMlAssetHeaderAudit.inspect(
                BoostMlAssetSchemaAudit.Kind.HYPO_RISK_V12, riskHeader
            ) == null) return fail(Finding.INVALID_RISK_HEADER)
        if (BoostMlAssetHeaderAudit.inspect(
                BoostMlAssetSchemaAudit.Kind.MEAL_LIKELIHOOD, mealHeader
            ) == null) return fail(Finding.INVALID_MEAL_HEADER)

        val expectedRisk = BoostMlAssetSchemaAudit.requiredFeatureNames(
            BoostMlAssetSchemaAudit.Kind.HYPO_RISK_V12
        )
        val expectedMeal = BoostMlAssetSchemaAudit.requiredFeatureNames(
            BoostMlAssetSchemaAudit.Kind.MEAL_LIKELIHOOD
        )
        fun difference(actual: List<String>, expected: List<String>): Int =
            (0 until minOf(actual.size, expected.size))
                .firstOrNull { actual[it] != expected[it] }
                ?: minOf(actual.size, expected.size)

        if (riskFeatureNames != expectedRisk)
            return fail(Finding.INVALID_RISK_FEATURE_ORDER,
                difference(riskFeatureNames, expectedRisk))
        if (mealFeatureNames != expectedMeal)
            return fail(Finding.INVALID_MEAL_FEATURE_ORDER,
                difference(mealFeatureNames, expectedMeal))
        val shared = riskFeatureNames.take(mealFeatureNames.size)
        if (shared != mealFeatureNames)
            return fail(Finding.SHARED_BASE_MISMATCH,
                difference(shared, mealFeatureNames))
        return Report(Finding.MATCH, null)
    }
}
