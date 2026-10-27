package app.aaps.plugins.boostport.telemetry

/**
 * Pure, inert KMP adapter for the original eight inputs passed to
 * BoostMealModel.predictMealLikelihood and the legacy
 * BoostRiskModel.predictHypoRisk entry point.
 *
 * Both original functions use the same positional feature order; in
 * particular the basal IOB component is signed and the requirement
 * is an already-calculated historical input, not a requested dose.
 *
 * A V12 model with 53 features must never be silently fed this vector:
 * the declared model schema is required to match the eight-feature
 * schema exactly or we abstain, as the source risk model does.
 *
 * Does not call a model, compute insulin, register with any live
 * algorithm, read patient data or communicate with a pump.
 */
internal object BoostMlLegacyVectorAudit {

    data class Input(
        val cgmMgdl: Double,
        val iobTotal: Double,
        val iobBasal: Double,
        val bgAboveTarget: Double,
        val directionNum: Double,
        val localHour: Int,
        val iobActivity: Double,
        val priorInsulinRequirement: Double,
    )

    data class Snapshot(
        val featureNames: List<String>,
        val featureValues: List<Double>,
        val format: String,
    )

    fun inspect(input: Input, declaredFeatureNames: List<String>): Snapshot? {
        if (declaredFeatureNames != BoostMlAssetSchemaAudit.BASE_FEATURES ||
            input.localHour !in 0..23 || input.cgmMgdl <= 0.0 ||
            input.directionNum !in -2.0..2.0
        ) return null
        val vector = listOf(
            input.cgmMgdl,
            input.iobTotal,
            input.iobBasal,
            input.bgAboveTarget,
            input.directionNum,
            input.localHour.toDouble(),
            input.iobActivity,
            input.priorInsulinRequirement,
        )
        if (vector.any { !it.isFinite() }) return null
        return Snapshot(
            featureNames = BoostMlAssetSchemaAudit.BASE_FEATURES.toList(),
            featureValues = vector,
            format = "legacy-eight-inputs",
        )
    }
}
