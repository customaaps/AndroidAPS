package app.aaps.plugins.boostport.telemetry

/**
 * Passive KMP extraction of BoostRiskModel.predictAtProjectedIob's
 * FEATURE-VECTOR REWRITE ONLY (legacy V1/V5, 2026-07-02).
 *
 * This is an offline schema comparison, NOT projected hypo-risk inference:
 * it never loads a LightGBM asset, scores a model, evaluates any SMB,
 * recommends insulin, changes IOB/targets/settings, or touches a pump.
 *
 * The risk model has both iob_iob and iob_iob_lag0 representing current
 * IOB. Both must change together. Lag1..lag5 historical values are
 * immutable evidence and are never modified by a hypothetical projection.
 */
internal object BoostRiskFeatureProjectionAudit {

    data class Projection(
        val featureNames: List<String>,
        val values: List<Double>,
        val currentIobBefore: Double,
        val hypotheticalIobAfter: Double,
        val hypotheticalIobDifference: Double,
        val changedFeatureNames: List<String>,
    )

    private const val MAX_FEATURES = 128

    fun inspect(
        featureNames: List<String>,
        original: List<Double>,
        hypotheticalIob: Double,
    ): Projection? {
        if (featureNames.isEmpty() || featureNames.size > MAX_FEATURES ||
            featureNames.size != original.size ||
            featureNames.any { it.isBlank() } ||
            featureNames.distinct().size != featureNames.size ||
            original.any { !it.isFinite() } || !hypotheticalIob.isFinite()
        ) return null

        // The source explicitly abstains if iob_iob isn't in the schema.
        val iobIndex = featureNames.indexOf("iob_iob")
        if (iobIndex < 0) return null
        val oldIob = original[iobIndex]
        val diff = hypotheticalIob - oldIob
        if (!diff.isFinite()) return null

        val result = original.toMutableList()
        result[iobIndex] = hypotheticalIob

        fun updateIfPresent(name: String, updated: (Double) -> Double) {
            val at = featureNames.indexOf(name)
            if (at >= 0) result[at] = updated(result[at])
        }
        updateIfPresent("iob_iob_lag0") { hypotheticalIob }
        updateIfPresent("iob_bolusiob") { (it + diff).coerceAtLeast(0.0) }
        updateIfPresent("recent_smb_units_60m") { (it + diff).coerceAtLeast(0.0) }
        updateIfPresent("recent_smb_units_60m_lag0") { (it + diff).coerceAtLeast(0.0) }
        updateIfPresent("time_since_last_smb_min") { 0.0 }

        if (result.any { !it.isFinite() }) return null
        val changed = featureNames.indices
            .filter { result[it] != original[it] }
            .map { featureNames[it] }
        return Projection(
            featureNames = featureNames.toList(),
            values = result.toList(),
            currentIobBefore = oldIob,
            hypotheticalIobAfter = hypotheticalIob,
            hypotheticalIobDifference = diff,
            changedFeatureNames = changed,
        )
    }
}
