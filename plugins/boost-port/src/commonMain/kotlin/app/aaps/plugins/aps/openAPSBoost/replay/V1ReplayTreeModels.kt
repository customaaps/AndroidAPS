package app.aaps.plugins.aps.openAPSBoost.replay

import app.aaps.plugins.boostport.telemetry.BoostMlAssetSchemaAudit
import app.aaps.plugins.boostport.telemetry.BoostMlTreeDiagnostics
import kotlin.math.exp

/**
 * Source model inference for the standalone replay engine. Factories accept only
 * the complete bundled schemas; parsing/loading and lifetime belong to the caller.
 * No live APS, DI, preferences, sensor reads or pump commands are involved.
 *
 * Unlike the stricter diagnostic evaluator, risk traversal preserves the source's
 * nonfinite-input right branch. Meal traversal preserves its direct <= comparison.
 */
internal class V1ReplayRiskTreeModel private constructor(
    private val model: BoostMlTreeDiagnostics.Model,
) : V1ReplayRiskModel {
    private var lastFeatures: DoubleArray? = null

    companion object {
        fun from(model: BoostMlTreeDiagnostics.Model): V1ReplayRiskTreeModel? {
            val snapshot = model.copy(featureNames = model.featureNames.toList(), trees = model.trees.toList())
            if (BoostMlAssetSchemaAudit.inspect(BoostMlAssetSchemaAudit.Kind.HYPO_RISK_V12, snapshot) == null) return null
            return V1ReplayRiskTreeModel(snapshot)
        }
    }

    override fun getFeatureNames(): List<String> = model.featureNames.toList()

    override fun predict(features: DoubleArray): Double? {
        if (features.size != model.featureNames.size) return null
        lastFeatures = features.copyOf()
        return score(model, features, risk = true)
    }

    override fun predictHypoRisk(
        cgmMgdl: Double, iobTotal: Double, iobBasal: Double,
        bgAboveTarget: Double, directionNum: Double, hour: Int,
        iobActivity: Double, insulinReq: Double,
    ): Double? = predict(doubleArrayOf(
        cgmMgdl, iobTotal, iobBasal, bgAboveTarget,
        directionNum, hour.toDouble(), iobActivity, insulinReq,
    ))

    /** Source projection; it neither replaces the cached cycle nor accumulates hypothetical SMBs. */
    fun predictAtProjectedIob(projectedIob: Double): Double? {
        val base = lastFeatures ?: return null
        val names = model.featureNames
        val iobIdx = names.indexOf("iob_iob")
        if (iobIdx < 0 || iobIdx >= base.size) return null
        val f = base.copyOf()
        val delta = projectedIob - f[iobIdx]
        f[iobIdx] = projectedIob
        fun set(name: String, v: (Double) -> Double) {
            val i = names.indexOf(name)
            if (i in f.indices) f[i] = v(f[i])
        }
        set("iob_iob_lag0") { projectedIob }
        set("iob_bolusiob") { (it + delta).coerceAtLeast(0.0) }
        set("recent_smb_units_60m") { (it + delta).coerceAtLeast(0.0) }
        set("recent_smb_units_60m_lag0") { (it + delta).coerceAtLeast(0.0) }
        set("time_since_last_smb_min") { 0.0 }
        return score(model, f, risk = true)
    }
}

internal class V1ReplayMealTreeModel private constructor(
    private val model: BoostMlTreeDiagnostics.Model,
) : V1ReplayMealModel {
    companion object {
        fun from(model: BoostMlTreeDiagnostics.Model): V1ReplayMealTreeModel? {
            val snapshot = model.copy(featureNames = model.featureNames.toList(), trees = model.trees.toList())
            if (BoostMlAssetSchemaAudit.inspect(BoostMlAssetSchemaAudit.Kind.MEAL_LIKELIHOOD, snapshot) == null) return null
            return V1ReplayMealTreeModel(snapshot)
        }
    }

    override fun predictMealLikelihood(
        cgmMgdl: Double, iobTotal: Double, iobBasal: Double,
        bgAboveTarget: Double, directionNum: Double, hour: Int,
        iobActivity: Double, insulinReq: Double,
    ): Double = score(model, doubleArrayOf(
        cgmMgdl, iobTotal, iobBasal, bgAboveTarget,
        directionNum, hour.toDouble(), iobActivity, insulinReq,
    ), risk = false)
}

/** Input trees have already been checked in full, including all unused branches. */
private fun score(model: BoostMlTreeDiagnostics.Model, features: DoubleArray, risk: Boolean): Double {
    fun walk(node: BoostMlTreeDiagnostics.Node): Double = when (node) {
        is BoostMlTreeDiagnostics.Leaf -> node.value
        is BoostMlTreeDiagnostics.Split -> {
            val v = features[node.featureIndex]
            val left = if (risk) v.isFinite() && v <= node.threshold else v <= node.threshold
            walk(if (left) node.left else node.right)
        }
    }
    var rawScore = 0.0
    for (tree in model.trees) rawScore += walk(tree)
    return 1.0 / (1.0 + exp(-rawScore))
}
