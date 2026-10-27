package app.aaps.plugins.aps.openAPSBoost

import kotlin.math.min

/**
 * Non-actuating extraction of V1 prediction horizon and SMB vetoes
 * (DetermineBasalBoost.kt, around lines 1000–1037).
 * Does not calculate carbohydrate treatments or authorize insulin.
 */
internal object BoostV1PredictionSafetyTimeline {
    data class Input(
        val bg: Double,
        val minBg: Double,
        val lgsThreshold: Double,
        val naiveEventualBg: Double,
        val minGuardBg: Double,
        val mealCob: Double,
        val carbImpact: Double,
        val remainingCarbImpactPeak: Double,
        val cobPredictions: List<Int>,
        val iobPredictions: List<Int>,
        val maxDelta: Double,
        val smbConditionMatched: Boolean
    )
    enum class Finding { INVALID_INPUT, READY }
    data class Result(
        val finding: Finding,
        val minutesAboveMinBg: Int? = null,
        val minutesAboveThreshold: Int? = null,
        val glucoseUndershoot: Double? = null,
        val guardedSmbConditionMatched: Boolean = false,
        val lowGuardVeto: Boolean = false,
        val rapidDeltaVeto: Boolean = false,
        val mayDeliverInsulin: Boolean = false
    )
    fun inspect(i: Input): Result {
        val nums = listOf(i.bg, i.minBg, i.lgsThreshold, i.naiveEventualBg,
            i.minGuardBg, i.mealCob, i.carbImpact, i.remainingCarbImpactPeak, i.maxDelta)
        if (nums.any { !it.isFinite() } || i.bg <= 0.0 || i.minBg <= 0.0 ||
            i.lgsThreshold <= 0.0 || i.mealCob < 0.0 ||
            i.cobPredictions.any { it < 0 } || i.iobPredictions.any { it < 0 })
            return Result(Finding.INVALID_INPUT)
        val useCob = i.mealCob > 0.0 && (i.carbImpact > 0.0 || i.remainingCarbImpactPeak > 0.0)
        val predicted = if (useCob) i.cobPredictions else i.iobPredictions
        if (predicted.isEmpty()) return Result(Finding.INVALID_INPUT)
        fun firstBelow(target: Double): Int {
            val pos = predicted.indexOfFirst { it < target }
            return if (pos == -1) 240 else pos * 5
        }
        val minutesAboveMinBg = firstBelow(i.minBg)
        val minutesAboveThreshold = firstBelow(i.lgsThreshold)
        val carbsReqBg = if (i.naiveEventualBg < 40.0)
            min(i.minGuardBg, i.naiveEventualBg) else i.naiveEventualBg
        val lowVeto = i.minGuardBg < i.lgsThreshold
        val rapidVeto = i.maxDelta > 0.30 * i.bg
        return Result(
            finding = Finding.READY,
            minutesAboveMinBg = minutesAboveMinBg,
            minutesAboveThreshold = minutesAboveThreshold,
            glucoseUndershoot = i.lgsThreshold - carbsReqBg,
            guardedSmbConditionMatched = i.smbConditionMatched && !lowVeto && !rapidVeto,
            lowGuardVeto = lowVeto,
            rapidDeltaVeto = rapidVeto
        )
    }
}
