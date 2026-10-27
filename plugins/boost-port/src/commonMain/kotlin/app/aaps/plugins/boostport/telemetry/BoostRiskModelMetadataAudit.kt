package app.aaps.plugins.boostport.telemetry

/**
 * Passive, offline validation of the hypo-risk v12 asset's declared metadata.
 * Inputs must come from a separately parsed copy of hypo_risk_meta.json.
 * This does not open assets, run the model, read sensors, perform APS
 * calculations, authorize insulin delivery or communicate with the pump.
 */
internal object BoostRiskModelMetadataAudit {
    enum class Finding {
        INVALID_FEATURE_ORDER, INVALID_STATIC_FEATURES,
        INVALID_WINDOWED_FEATURES, INVALID_LOOKBACK,
        INVALID_HORIZON, INVALID_TRAINING_COUNTS, MATCH
    }

    data class Report(val finding: Finding, val firstDifference: Int?)

    fun inspect(
        features: List<String>,
        staticFeatures: List<String>,
        windowedFeatures: List<String>,
        lookbackCycles: Int,
        horizonMinutes: Int,
        trainingSamples: Int,
        trainingUsers: Int,
    ): Report {
        fun mismatch(actual: List<String>, expected: List<String>): Int =
            (0 until minOf(actual.size, expected.size))
                .firstOrNull { actual[it] != expected[it] }
                ?: minOf(actual.size, expected.size)

        val canonical = BoostMlAssetSchemaAudit.requiredFeatureNames(
            BoostMlAssetSchemaAudit.Kind.HYPO_RISK_V12
        )
        val expectedStatic = BoostMlFeatureOrderAudit.BASE
        val expectedWindowed = canonical.drop(expectedStatic.size)
        if (features != canonical)
            return Report(Finding.INVALID_FEATURE_ORDER, mismatch(features, canonical))
        if (staticFeatures != expectedStatic)
            return Report(Finding.INVALID_STATIC_FEATURES,
                mismatch(staticFeatures, expectedStatic))
        if (windowedFeatures != expectedWindowed)
            return Report(Finding.INVALID_WINDOWED_FEATURES,
                mismatch(windowedFeatures, expectedWindowed))
        if (lookbackCycles != BoostMlFeatureDiagnostics.LOOKBACK)
            return Report(Finding.INVALID_LOOKBACK, null)
        if (horizonMinutes != 90)
            return Report(Finding.INVALID_HORIZON, null)
        // Values declared by the archived v12 metadata; exact equality here
        // detects a substituted asset, not any model-quality guarantee.
        if (trainingSamples != 3_007_589 || trainingUsers != 32)
            return Report(Finding.INVALID_TRAINING_COUNTS, null)
        return Report(Finding.MATCH, null)
    }
}
