package app.aaps.plugins.boostport.telemetry

import app.aaps.plugins.aps.openAPSBoost.BoostV1PredictionArrayPostprocess

/** Attaches V1 source-style array visibility and trimming to offline inspection. */
internal object BoostV1PredictionReview {
    enum class Finding { INVALID_PREDICTION, READY }
    data class Input(
        val source: BoostV1UnifiedPredictionInspection.Input,
        val targetBg: Double,
        val mealCob: Double,
        val ci: Double,
        val remainingCiPeak: Double,
        val enableUam: Boolean
    )
    data class Report(
        val finding: Finding,
        val curves: BoostV1PredictionArrayPostprocess.Report? = null,
        val canDeliverInsulin: Boolean = false
    )
    fun inspect(i: Input): Report {
        val predicted = BoostV1UnifiedPredictionInspection.inspect(i.source)
        if (predicted.finding != BoostV1UnifiedPredictionInspection.Finding.READY)
            return Report(Finding.INVALID_PREDICTION)
        val arrays = predicted.prediction ?: return Report(Finding.INVALID_PREDICTION)
        if (i.targetBg != i.source.diagnostics.snapshot.engine.profile.targetMgDl ||
            i.mealCob != i.source.diagnostics.snapshot.engine.meal.cobGrams)
            return Report(Finding.INVALID_PREDICTION)
        val result = BoostV1PredictionArrayPostprocess.inspect(
            BoostV1PredictionArrayPostprocess.Input(
                arrays, i.targetBg, i.mealCob, i.ci, i.remainingCiPeak, i.enableUam
            )
        )
        return if (result.finding == BoostV1PredictionArrayPostprocess.Finding.READY)
            Report(Finding.READY, result) else Report(Finding.INVALID_PREDICTION)
    }
}
