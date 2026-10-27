package app.aaps.plugins.aps.openAPSBoost.replay

import app.aaps.core.interfaces.stats.TddCalculator

/** Recorded, already resolved settings; no Preferences or automatic application. */
internal data class BoostIsfSettings(
    val autosensMax: Double, val autosensMin: Double, val useTdd: Boolean,
    val velocity: Double, val bgCap: Double, val normalTarget: Double,
    val highTtRaisesSens: Boolean, val lowTtLowersSens: Boolean,
    val halfBasalTarget: Int, val adjustSensitivity: Boolean,
    val adjustmentFactor: Int, val autosensWhenNoTdd: Boolean,
)

/** profileSens has the source's activity/profile percentage adjustment applied upstream. */
internal data class BoostIsfInputs(
    val profileSens: Double, val profilePercent: Int, val targetBg: Double,
    val insulinDivisor: Int, val glucoseValue: Double,
    val isTempTarget: Boolean, val orefAutosensRatio: Double,
)

internal data class BoostTddParts(
    val average7Days: Double?, val average1Day: Double?,
    val last24Hours: Double?, val last4Hours: Double?, val last8To4Hours: Double?,
)

internal data class BoostIsfPreparationResult(
    val sensNormalTarget: Double,
    val variableSens: Double,
    val bgCapped: Double,
    val insulinDivisor: Int,
    val dynIsfVelocity: Double,
    val ratio: Double,
    val tdd: Double,
    val isfDebug: String,
)

/** Explicit read boundary using the target KMP API. No wall-clock or persisted writes. */
internal object BoostTargetTddReader {
    suspend fun read(calculator: TddCalculator, atMs: Long): BoostTddParts = BoostTddParts(
        average7Days = calculator.averageTDD(calculator.calculate(atMs, 7L, allowMissingDays = true))?.data?.totalAmount,
        average1Day = calculator.averageTDD(calculator.calculate(atMs, 1L, allowMissingDays = true))?.data?.totalAmount,
        last24Hours = calculator.calculateDaily(atMs, -24L, 0L)?.totalAmount,
        last4Hours = calculator.calculateDaily(atMs, -4L, 0L)?.totalAmount,
        last8To4Hours = calculator.calculateDaily(atMs, -8L, -4L)?.totalAmount,
    )
}
