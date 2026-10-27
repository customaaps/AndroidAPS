package app.aaps.plugins.aps.openAPSBoost

/**
 * Snapshot of the remaining caller-supplied V1 determine_basal arguments.
 * Together with BoostV1ApsTypeBoundary.Inputs this preserves the replay
 * invocation contract without enabling treatment or replacing Dmitry APS.
 *
 * Model outputs and historical glucose/SMB aggregates must be taken from
 * recorded inputs, not reconstructed from a later APSResult.
 */
internal object BoostV1DetermineBasalInvocation {
    data class Autosens(
        val ratio: Double
    )
    data class Meal(
        val carbs: Double,
        val mealCob: Double
    )
    data class ModelScores(
        val hypoRisk: Double? = null,
        val mealLikelihood: Double? = null
    )
    data class Inputs(
        val base: BoostV1ApsTypeBoundary.Inputs,
        val autosens: Autosens,
        val meal: Meal,
        val microBolusAllowed: Boolean,
        val recentSmbVolume60Min: Double,
        val cumulativeSmbCap60Min: Double,
        val recentLowBg45Min: Double,
        val timeSinceLastSmbMin: Double,
        val postRescueTightRampCap: Double?,
        val modelScores: ModelScores? = null
    )
    enum class Finding {
        READY_FOR_OFFLINE_REPLAY, INVALID_BASE, INVALID_AUTOSENS,
        INVALID_MEAL, INVALID_SMB_HISTORY, INVALID_GLUCOSE_HISTORY,
        INVALID_RESCUE_CONFIGURATION, INVALID_MODEL_SCORE
    }
    data class Report(
        val finding: Finding,
        val mayExecuteInsulinDosing: Boolean = false,
        val mayDispatchToPump: Boolean = false
    )

    fun inspect(input: Inputs): Report {
        if (BoostV1ApsTypeBoundary.validate(input.base) != BoostV1ApsTypeBoundary.Validation.VALID)
            return Report(Finding.INVALID_BASE)
        if (!input.autosens.ratio.isFinite() || input.autosens.ratio <= 0.0)
            return Report(Finding.INVALID_AUTOSENS)
        if (!input.meal.carbs.isFinite() || !input.meal.mealCob.isFinite() ||
            input.meal.carbs < 0 || input.meal.mealCob < 0)
            return Report(Finding.INVALID_MEAL)
        if (!input.recentSmbVolume60Min.isFinite() || input.recentSmbVolume60Min < 0 ||
            !input.cumulativeSmbCap60Min.isFinite() || input.cumulativeSmbCap60Min < 0 ||
            !input.timeSinceLastSmbMin.isFinite() || input.timeSinceLastSmbMin < 0)
            return Report(Finding.INVALID_SMB_HISTORY)
        if (!input.recentLowBg45Min.isFinite() || input.recentLowBg45Min <= 0)
            return Report(Finding.INVALID_GLUCOSE_HISTORY)
        if (input.postRescueTightRampCap != null &&
            (!input.postRescueTightRampCap.isFinite() ||
                input.postRescueTightRampCap < 0.0 || input.postRescueTightRampCap > 1.0))
            return Report(Finding.INVALID_RESCUE_CONFIGURATION)
        val scores = input.modelScores
        if (scores != null && listOfNotNull(scores.hypoRisk, scores.mealLikelihood)
                .any { !it.isFinite() || it < 0.0 || it > 1.0 })
            return Report(Finding.INVALID_MODEL_SCORE)
        return Report(Finding.READY_FOR_OFFLINE_REPLAY)
    }
}
