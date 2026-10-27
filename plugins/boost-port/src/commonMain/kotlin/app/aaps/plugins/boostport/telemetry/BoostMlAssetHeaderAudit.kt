package app.aaps.plugins.boostport.telemetry

/**
 * Read-only, platform-neutral validation of the actual Boost model headers
 * under app/src/main/assets/boost, before Android JSON tree parsing.
 *
 * Both legacy Boost LightGBM assets are binary classifiers with no average
 * output. A different model objective, a changed tree/feature count, or
 * missing metadata must never masquerade as the current inactive model.
 *
 * Data is supplied by the caller; no Android dependency, model scoring,
 * scheduling, APS calculation, insulin dosing, preferences or pump access.
 */
internal object BoostMlAssetHeaderAudit {

    data class Header(
        val declaredTrees: Int,
        val declaredFeatures: Int,
        val objective: String,
        val averageOutput: Boolean,
    )

    data class Check(
        val kind: BoostMlAssetSchemaAudit.Kind,
        val declaredTrees: Int,
        val declaredFeatures: Int,
        val objective: String,
    )

    fun inspect(
        kind: BoostMlAssetSchemaAudit.Kind,
        header: Header,
    ): Check? {
        if (header.declaredTrees != BoostMlAssetSchemaAudit.requiredTreeCount(kind) ||
            header.declaredFeatures != BoostMlAssetSchemaAudit.requiredFeatureNames(kind).size ||
            header.objective != "binary" ||
            header.averageOutput
        ) return null
        return Check(
            kind = kind,
            declaredTrees = header.declaredTrees,
            declaredFeatures = header.declaredFeatures,
            objective = header.objective,
        )
    }
}
