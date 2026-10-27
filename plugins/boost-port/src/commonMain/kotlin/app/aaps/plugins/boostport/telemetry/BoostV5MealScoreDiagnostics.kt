package app.aaps.plugins.boostport.telemetry

import kotlin.math.exp

/**
 * Passive KMP adaptation of Boost V5 MealSignalScore.
 *
 * Reproduces the 2026-07-06 seven-weight normalisation, including the
 * corrected ML-outage restoration factor TOTAL/(TOTAL-ML). These values are
 * for offline telemetry parity; there is no MealHypothesis state transition,
 * SMB budget, target adjustment, APS invocation, or pump interface.
 */
internal object BoostV5MealScoreDiagnostics {
    const val WEIGHT_DELTA = 0.30
    const val WEIGHT_DELTA_ACCEL = 0.16
    const val WEIGHT_ML_MEAL = 0.20
    const val WEIGHT_NOT_RECENT_LOW = 0.12
    const val WEIGHT_MEAL_HOUR = 0.10
    const val WEIGHT_NOT_EXERCISING = 0.04
    const val WEIGHT_SUSTAINED_RISE = 0.15
    const val TOTAL_WEIGHT = WEIGHT_DELTA + WEIGHT_DELTA_ACCEL +
        WEIGHT_ML_MEAL + WEIGHT_NOT_RECENT_LOW + WEIGHT_MEAL_HOUR +
        WEIGHT_NOT_EXERCISING + WEIGHT_SUSTAINED_RISE
    const val ML_OUTAGE_AFTER_CYCLES = 3
    val ML_RENORMALIZATION = TOTAL_WEIGHT / (TOTAL_WEIGHT - WEIGHT_ML_MEAL)

    data class Inputs(
        val delta: Double,
        val deltaAccelerationPercent: Double,
        val mlMealLikely: Double?,
        val recentLowBg: Double,
        val hour: Int,
        val exerciseActive: Boolean,
        val cumulativeRise30Min: Double,
        val mlNullStreak: Int = 0,
    )

    data class Terms(
        val delta: Double,
        val deltaAcceleration: Double,
        val mlMeal: Double,
        val notRecentlyLow: Double,
        val mealHour: Double,
        val notExercising: Double,
        val sustainedRise: Double,
    )

    data class Result(
        val score: Double,
        val terms: Terms,
        val renormalizedForMissingMl: Boolean,
    )

    fun score(input: Inputs): Result? {
        if (!input.delta.isFinite() || !input.deltaAccelerationPercent.isFinite() ||
            input.mlMealLikely?.let { !it.isFinite() || it !in 0.0..1.0 } == true ||
            !input.recentLowBg.isFinite() || input.recentLowBg <= 0.0 ||
            input.hour !in 0..23 || !input.cumulativeRise30Min.isFinite() ||
            input.mlNullStreak < 0
        ) return null

        val terms = Terms(
            delta = clipNormalize(input.delta, 0.0, 20.0),
            deltaAcceleration = clipNormalize(input.deltaAccelerationPercent, 0.0, 30.0),
            mlMeal = input.mlMealLikely ?: 0.0,
            notRecentlyLow = maxOf(0.4, clipNormalize(input.recentLowBg, 70.0, 100.0)),
            mealHour = mealHourBump(input.hour),
            notExercising = if (input.exerciseActive) 0.0 else 1.0,
            sustainedRise = clipNormalize(input.cumulativeRise30Min, 20.0, 60.0),
        )
        val renormalize = input.mlMealLikely == null &&
            input.mlNullStreak >= ML_OUTAGE_AFTER_CYCLES
        var weighted = WEIGHT_DELTA * terms.delta +
            WEIGHT_DELTA_ACCEL * terms.deltaAcceleration +
            WEIGHT_NOT_RECENT_LOW * terms.notRecentlyLow +
            WEIGHT_MEAL_HOUR * terms.mealHour +
            WEIGHT_NOT_EXERCISING * terms.notExercising +
            WEIGHT_SUSTAINED_RISE * terms.sustainedRise
        if (renormalize) weighted *= ML_RENORMALIZATION
        else weighted += WEIGHT_ML_MEAL * terms.mlMeal

        return Result(weighted.coerceIn(0.0, 1.0), terms, renormalize)
    }

    private fun clipNormalize(value: Double, low: Double, high: Double): Double =
        ((value - low) / (high - low)).coerceIn(0.0, 1.0)

    /** Legacy Gaussian prior at 08:00, 13:00 and 19:00 with a 2h width. */
    private fun mealHourBump(hour: Int): Double =
        listOf(8, 13, 19).maxOf { center ->
            val diff = hour.toDouble() - center
            exp(-diff * diff / 8.0)
        }
}
