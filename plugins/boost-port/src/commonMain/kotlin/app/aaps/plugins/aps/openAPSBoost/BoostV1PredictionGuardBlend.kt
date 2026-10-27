package app.aaps.plugins.aps.openAPSBoost

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Read-only extraction of the V1 guard / predicted-low blending branch
 * (DetermineBasalBoost.kt, source lines ~918-974).
 *
 * Values such as minIOBPredBG and minZTGuardBG must be calculated by the
 * matching original-cycle prediction pass. No insulin instruction is produced.
 */
internal object BoostV1PredictionGuardBlend {
    data class Input(
        val mealCarbs: Double,
        val mealCob: Double,
        val enableUam: Boolean,
        val carbImpactDuration: Double,
        val remainingCarbImpactPeak: Double,
        val iobPredBg: Double,
        val cobPredBg: Double?,
        val uamPredBg: Double?,
        val minIobPredBg: Double,
        val minCobPredBg: Double,
        val minUamPredBg: Double,
        val minZtGuardBg: Double,
        val minCobGuardBg: Double,
        val minUamGuardBg: Double,
        val minIobGuardBg: Double,
        val maxCobPredBg: Double,
        val currentBg: Double,
        val threshold: Double,
        val targetBg: Double
    )
    data class Result(
        val averagePredBg: Double,
        val minGuardBg: Double,
        val minPredBg: Double,
        val minZtUamPredBg: Double,
        val mayDispatchToPump: Boolean = false
    )

    fun inspect(i: Input): Result? {
        val values = listOfNotNull(
            i.mealCarbs, i.mealCob, i.carbImpactDuration, i.remainingCarbImpactPeak,
            i.iobPredBg, i.cobPredBg, i.uamPredBg, i.minIobPredBg, i.minCobPredBg,
            i.minUamPredBg, i.minZtGuardBg, i.minCobGuardBg, i.minUamGuardBg,
            i.minIobGuardBg, i.maxCobPredBg, i.currentBg, i.threshold, i.targetBg
        )
        if (values.any { !it.isFinite() } || i.mealCarbs < 0.0 || i.mealCob < 0.0 ||
            i.currentBg <= 0.0 || i.targetBg <= 0.0 || i.threshold <= 0.0 ||
            i.carbImpactDuration < 0.0 || i.remainingCarbImpactPeak < 0.0)
            return null
        // A no-carb cycle is valid in the source's IOB/UAM branches. Only
        // require the COB fraction in branches that actually consume it;
        // missing evidence must not be replaced with a fabricated ratio.
        if (i.mealCarbs == 0.0 && i.mealCob > 0.0) return null
        val fraction = if (i.mealCarbs > 0.0) i.mealCob / i.mealCarbs else null
        // DetermineBasalBoost.round(value, 0) uses Java Math.round: ties
        // go toward positive infinity, unlike kotlin.math.round's ties-to-even.
        fun round(value: Double): Double =
            if (value.isFinite()) value.roundToLong().toDouble() else value
        val cob = i.cobPredBg
        val uam = i.uamPredBg
        var avgPred = when {
            i.minUamPredBg < 999.0 && i.minCobPredBg < 999.0 -> {
                val portion = fraction ?: return null
                round((1.0 - portion) * (uam ?: return null) + portion * (cob ?: return null))
            }
            i.minCobPredBg < 999.0 -> round((i.iobPredBg + (cob ?: return null)) / 2.0)
            i.minUamPredBg < 999.0 -> round((i.iobPredBg + (uam ?: return null)) / 2.0)
            else -> round(i.iobPredBg)
        }
        if (i.minZtGuardBg > avgPred) avgPred = i.minZtGuardBg
        val minGuard = when {
            i.carbImpactDuration > 0.0 || i.remainingCarbImpactPeak > 0.0 ->
                if (i.enableUam) {
                    val portion = fraction ?: return null
                    portion * i.minCobGuardBg + (1.0 - portion) * i.minUamGuardBg
                } else i.minCobGuardBg
            i.enableUam -> i.minUamGuardBg
            else -> i.minIobGuardBg
        }.let { round(it) }
        var minZtUam = i.minUamPredBg
        when {
            i.minZtGuardBg < i.threshold ->
                minZtUam = (i.minUamPredBg + i.minZtGuardBg) / 2.0
            i.minZtGuardBg < i.targetBg -> {
                val denominator = i.targetBg - i.threshold
                if (denominator <= 0.0) return null
                val blend = (i.minZtGuardBg - i.threshold) / denominator
                val blended = i.minUamPredBg * blend + i.minZtGuardBg * (1.0 - blend)
                minZtUam = (i.minUamPredBg + blended) / 2.0
            }
            i.minZtGuardBg > i.minUamPredBg ->
                minZtUam = (i.minUamPredBg + i.minZtGuardBg) / 2.0
        }
        minZtUam = round(minZtUam)
        var minPred = round(i.minIobPredBg)
        if (i.mealCarbs != 0.0) {
            minPred = when {
                !i.enableUam && i.minCobPredBg < 999.0 ->
                    round(max(i.minIobPredBg, i.minCobPredBg))
                i.minCobPredBg < 999.0 -> {
                    val portion = fraction ?: return null
                    val blended = portion * i.minCobPredBg + (1.0 - portion) * minZtUam
                    round(max(i.minIobPredBg, max(i.minCobPredBg, blended)))
                }
                i.enableUam -> minZtUam
                else -> minGuard
            }
        } else if (i.enableUam) {
            minPred = round(max(i.minIobPredBg, minZtUam))
        }
        minPred = min(minPred, avgPred)
        if (i.maxCobPredBg > i.currentBg) minPred = min(minPred, i.maxCobPredBg)
        if (!listOf(avgPred, minGuard, minPred, minZtUam).all { it.isFinite() })
            return null
        return Result(avgPred, minGuard, minPred, minZtUam)
    }
}
