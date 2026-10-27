package app.aaps.plugins.boostport.telemetry

import app.aaps.plugins.aps.openAPSBoost.BoostV1PredictionArraysShadow

/** Combines source-derived V1 diagnostic components and prediction arrays. */
internal object BoostV1UnifiedPredictionInspection {
    enum class Finding { INVALID_CONTEXT, INVALID_PREDICTION, READY }
    data class Input(
        val diagnostics: BoostV1UnifiedOfflineInspection.Input,
        val prediction: BoostV1PredictionArraysShadow.Input
    )
    data class Report(
        val finding: Finding,
        val prediction: BoostV1PredictionArraysShadow.Report? = null,
        val canDeliverInsulin: Boolean = false
    )
    fun inspect(i: Input): Report {
        if (BoostV1UnifiedOfflineInspection.inspect(i.diagnostics).finding !=
            BoostV1UnifiedOfflineInspection.Finding.READY ||
            i.prediction.bg != i.diagnostics.snapshot.engine.glucose.mgDl)
            return Report(Finding.INVALID_CONTEXT)
        val arrays = BoostV1PredictionArraysShadow.inspect(i.prediction)
        if (arrays.finding != BoostV1PredictionArraysShadow.Finding.READY)
            return Report(Finding.INVALID_PREDICTION)
        return Report(Finding.READY, arrays)
    }
}
