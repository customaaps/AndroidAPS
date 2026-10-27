package app.aaps.plugins.boostport.telemetry

import app.aaps.plugins.aps.openAPSBoost.BoostV1CarbAbsorptionShadow
import app.aaps.plugins.aps.openAPSBoost.BoostV1PredictionArraysShadow
import app.aaps.plugins.aps.openAPSBoost.BoostV1PredictionArrayPostprocess

/**
 * Offline composition of source V1 meal pre-calculation, dynamic-ISF forecast
 * arrays and their historical visibility/trimming logic.
 * All outputs are observations, never commands to the live APS.
 */
internal object BoostV1ForecastPipeline {
    enum class Finding { INVALID_MEAL, INCONSISTENT_CONTEXT, INVALID_ARRAYS, INVALID_REVIEW, READY }
    data class Input(
        val meal: BoostV1CarbAbsorptionShadow.Input,
        val forecast: BoostV1PredictionArraysShadow.Input,
        val targetMgDl: Double,
        val enableUam: Boolean
    )
    data class Report(
        val finding: Finding,
        val observations: BoostV1PredictionArrayPostprocess.Report? = null,
        val canDeliverInsulin: Boolean = false
    )
    fun inspect(input: Input): Report {
        val absorption = BoostV1CarbAbsorptionShadow.inspect(input.meal)
        if (absorption.finding != BoostV1CarbAbsorptionShadow.Finding.READY)
            return Report(Finding.INVALID_MEAL)
        // Prevent a caller from mixing absorption math from one profile/meal
        // with independently constructed forecast inputs from another.
        if (input.forecast.ci != absorption.ci ||
            input.forecast.cid != absorption.cid ||
            input.forecast.acid != absorption.acid ||
            input.forecast.remainingCATimeHours != absorption.remainingCaHours ||
            input.forecast.remainingCIpeak != absorption.remainingCiPeak ||
            input.forecast.slopeFromDeviations != absorption.slopeFromDeviations ||
            input.forecast.sens <= 0.0 ||
            input.forecast.bg <= 0.0)
            return Report(Finding.INCONSISTENT_CONTEXT)
        val arrays = BoostV1PredictionArraysShadow.inspect(input.forecast)
        if (arrays.finding != BoostV1PredictionArraysShadow.Finding.READY)
            return Report(Finding.INVALID_ARRAYS)
        val review = BoostV1PredictionArrayPostprocess.inspect(
            BoostV1PredictionArrayPostprocess.Input(
                arrays = arrays,
                targetBg = input.targetMgDl,
                mealCob = input.meal.cob,
                ci = absorption.ci ?: return Report(Finding.INVALID_MEAL),
                remainingCiPeak = absorption.remainingCiPeak ?: return Report(Finding.INVALID_MEAL),
                enableUam = input.enableUam
            )
        )
        return if (review.finding == BoostV1PredictionArrayPostprocess.Finding.READY)
            Report(Finding.READY, review)
        else Report(Finding.INVALID_REVIEW)
    }
}
