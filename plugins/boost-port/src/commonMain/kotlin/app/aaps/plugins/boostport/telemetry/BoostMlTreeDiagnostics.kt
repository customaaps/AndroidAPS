package app.aaps.plugins.boostport.telemetry

import kotlin.math.exp

/**
 * Pure KMP staging of the shared LightGBM leaf-sum + logistic tree traversal
 * in the source BoostRiskModel and BoostMealModel.
 *
 * No asset loader, model registration, inference scheduling, AAPS or pump
 * connection. This evaluator is for offline parity tests only. Predictions
 * are NOT clinical decisions or recommendations for insulin dosing.
 */
internal object BoostMlTreeDiagnostics {
    sealed interface Node
    data class Leaf(val value: Double) : Node
    data class Split(
        val featureIndex: Int,
        val threshold: Double,
        val left: Node,
        val right: Node,
    ) : Node

    data class Model(
        val featureNames: List<String>,
        val trees: List<Node>,
    )

    data class Score(
        val rawLeafSum: Double,
        val logisticValue: Double,
        val contributingTrees: Int,
    )

    private const val MAX_FEATURES = 128
    private const val MAX_TREES = 512
    private const val MAX_DEPTH = 64

    /**
     * Source models score by summing one leaf per tree and then applying a
     * sigmoid. Unlike the legacy active path, malformed, absent or nonfinite
     * features cause abstention rather than traversing an arbitrary branch.
     */
    fun score(model: Model, featureVector: DoubleArray): Score? {
        if (model.featureNames.isEmpty() || model.featureNames.size > MAX_FEATURES ||
            model.featureNames.size != featureVector.size ||
            model.featureNames.any { it.isBlank() } ||
            model.featureNames.distinct().size != model.featureNames.size ||
            model.trees.isEmpty() || model.trees.size > MAX_TREES ||
            featureVector.any { !it.isFinite() }
        ) return null

        var raw = 0.0
        for (tree in model.trees) {
            val leaf = walk(tree, featureVector) ?: return null
            raw += leaf
            if (!raw.isFinite()) return null
        }
        val probability = 1.0 / (1.0 + exp(-raw))
        return if (probability.isFinite()) Score(raw, probability, model.trees.size)
        else null
    }

    private fun walk(root: Node, features: DoubleArray): Double? {
        var node = root
        repeat(MAX_DEPTH) {
            when (val next = node) {
                is Leaf -> return next.value.takeIf { it.isFinite() }
                is Split -> {
                    if (next.featureIndex !in features.indices || !next.threshold.isFinite()) {
                        return null
                    }
                    node = if (features[next.featureIndex] <= next.threshold) next.left else next.right
                }
            }
        }
        return null
    }
}
