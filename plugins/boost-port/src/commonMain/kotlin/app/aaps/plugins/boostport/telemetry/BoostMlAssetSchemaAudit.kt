package app.aaps.plugins.boostport.telemetry

/**
 * Pure source-parity validation for the two inactive Boost ML asset formats.
 *
 * Legacy BoostRiskModel uses 100 LightGBM trees and 53 ordered v12 inputs;
 * BoostMealModel uses 50 trees and the original eight inputs. Model data is
 * caller supplied: this does not load assets, run inference, read CGM/IOB,
 * authorize insulin decisions, register APS or communicate with a pump.
 */
internal object BoostMlAssetSchemaAudit {
    enum class Kind { HYPO_RISK_V12, MEAL_LIKELIHOOD }

    val BASE_FEATURES = listOf(
        "cgm_mgdl", "iob_iob", "iob_basaliob", "bg_above_target",
        "direction_num", "hour", "iob_activity", "sug_insulinReq",
    )
    val RISK_EXTRA_FEATURES = listOf(
        "sug_COB", "sug_eventualBG", "sug_expectedDelta", "sug_minDelta",
        "sug_TDD", "iob_bolusiob", "iob_netbasalinsulin",
        "recent_smb_units_60m", "time_since_last_smb_min",
    )
    val RISK_FEATURES = BASE_FEATURES + RISK_EXTRA_FEATURES +
        BoostMlFeatureDiagnostics.LOOKBACK_FEATURES.flatMap { feature ->
            (0 until BoostMlFeatureDiagnostics.LOOKBACK).map { lag ->
                "${feature}_lag$lag"
            }
        }

    fun requiredFeatureNames(kind: Kind): List<String> = when (kind) {
        Kind.HYPO_RISK_V12 -> RISK_FEATURES
        Kind.MEAL_LIKELIHOOD -> BASE_FEATURES
    }

    fun requiredTreeCount(kind: Kind): Int = when (kind) {
        Kind.HYPO_RISK_V12 -> 100
        Kind.MEAL_LIKELIHOOD -> 50
    }

    data class Summary(
        val kind: Kind,
        val trees: Int,
        val features: Int,
        val traversedNodes: Int,
        val depthMax: Int,
    )

    /**
     * Fail closed on a structurally inconsistent model, including a
     * malformed tree that might otherwise fail only on a rare branch.
     */
    fun inspect(
        kind: Kind,
        model: BoostMlTreeDiagnostics.Model,
    ): Summary? {
        val expectedNames = requiredFeatureNames(kind)
        if (model.featureNames != expectedNames ||
            model.trees.size != requiredTreeCount(kind)
        ) return null
        var nodeCount = 0
        var largestDepth = 0
        fun check(node: BoostMlTreeDiagnostics.Node, depth: Int): Boolean {
            if (depth > 64 || ++nodeCount > 150_000) return false
            largestDepth = maxOf(largestDepth, depth)
            return when (node) {
                is BoostMlTreeDiagnostics.Leaf -> node.value.isFinite()
                is BoostMlTreeDiagnostics.Split ->
                    node.featureIndex in expectedNames.indices &&
                        node.threshold.isFinite() &&
                        check(node.left, depth + 1) &&
                        check(node.right, depth + 1)
            }
        }
        if (!model.trees.all { check(it, 1) }) return null
        return Summary(kind, model.trees.size, expectedNames.size, nodeCount, largestDepth)
    }
}
