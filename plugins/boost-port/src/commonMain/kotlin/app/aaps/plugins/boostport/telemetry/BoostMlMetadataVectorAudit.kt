package app.aaps.plugins.boostport.telemetry

/**
 * Pure metadata/diagnostic-vector compatibility audit for the V6 risk model.
 * Metadata fields are supplied by the caller after parsing an offline asset.
 * No JSON parsing, asset I/O, model scoring, live CGM, preferences, APS,
 * insulin calculation or pump calls are performed.
 */
internal object BoostMlMetadataVectorAudit {
    enum class Finding {
        INVALID_METADATA, WRONG_LOOKBACK, FEATURE_ORDER_MISMATCH,
        STATIC_FEATURE_MISMATCH, WINDOW_FEATURE_MISMATCH,
        INVALID_VECTOR, MATCH
    }

    data class Report(val finding: Finding, val firstMismatch: Int?)

    fun inspect(
        metadataFeatures: List<String>,
        metadataStaticFeatures: List<String>,
        metadataWindowedFeatures: List<String>,
        metadataLookbackCycles: Int,
        diagnosticVector: DoubleArray,
    ): Report {
        fun fail(f: Finding, at: Int? = null) = Report(f, at)
        val canonical = BoostMlFeatureOrderAudit.EXPECTED
        val staticNames = BoostMlFeatureOrderAudit.BASE
        val windowNames = canonical.drop(staticNames.size)
        if (metadataFeatures.size != canonical.size ||
            metadataStaticFeatures.size != staticNames.size ||
            metadataWindowedFeatures.size != windowNames.size ||
            metadataFeatures.distinct().size != metadataFeatures.size
        ) return fail(Finding.INVALID_METADATA)
        if (metadataLookbackCycles != BoostMlFeatureDiagnostics.LOOKBACK)
            return fail(Finding.WRONG_LOOKBACK)
        fun firstMismatch(actual: List<String>, expected: List<String>): Int? =
            actual.indices.firstOrNull { actual[it] != expected[it] }
        firstMismatch(metadataFeatures, canonical)?.let {
            return fail(Finding.FEATURE_ORDER_MISMATCH, it)
        }
        firstMismatch(metadataStaticFeatures, staticNames)?.let {
            return fail(Finding.STATIC_FEATURE_MISMATCH, it)
        }
        firstMismatch(metadataWindowedFeatures, windowNames)?.let {
            return fail(Finding.WINDOW_FEATURE_MISMATCH, it)
        }
        if (diagnosticVector.size != canonical.size)
            return fail(Finding.INVALID_VECTOR)
        diagnosticVector.indices.firstOrNull { !diagnosticVector[it].isFinite() }?.let {
            return fail(Finding.INVALID_VECTOR, it)
        }
        return Report(Finding.MATCH, null)
    }
}
