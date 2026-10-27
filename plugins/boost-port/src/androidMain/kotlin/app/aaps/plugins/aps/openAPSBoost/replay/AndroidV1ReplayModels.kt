package app.aaps.plugins.aps.openAPSBoost.replay

import android.content.Context
import app.aaps.plugins.boostport.telemetry.AndroidBoostMlAssetInspection
import app.aaps.plugins.boostport.telemetry.BoostMlAssetSchemaAudit

/** Explicit caller-owned loading for offline replay. Never injected or invoked by live APS. */
internal object AndroidV1ReplayModels {
    data class Loaded(
        val risk: V1ReplayRiskTreeModel?,
        val meal: V1ReplayMealTreeModel?,
        val unavailable: Map<BoostMlAssetSchemaAudit.Kind, String>,
    )

    fun load(context: Context): Loaded {
        val reader = AndroidBoostMlAssetInspection(context.applicationContext)
        val missing = mutableMapOf<BoostMlAssetSchemaAudit.Kind, String>()
        fun model(kind: BoostMlAssetSchemaAudit.Kind) = when (val result = reader.loadForOfflineInspection(kind)) {
            is AndroidBoostMlAssetInspection.Result.Ready -> result.model
            is AndroidBoostMlAssetInspection.Result.Unavailable -> {
                missing[kind] = result.reason
                null
            }
        }
        val risk = model(BoostMlAssetSchemaAudit.Kind.HYPO_RISK_V12)?.let(V1ReplayRiskTreeModel::from)
        val meal = model(BoostMlAssetSchemaAudit.Kind.MEAL_LIKELIHOOD)?.let(V1ReplayMealTreeModel::from)
        return Loaded(risk, meal, missing.toMap())
    }
}
