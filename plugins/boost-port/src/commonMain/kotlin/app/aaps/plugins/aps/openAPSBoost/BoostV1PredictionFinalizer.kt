package app.aaps.plugins.aps.openAPSBoost

import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Literal, non-actuating migration of the V1 prediction-array finalization
 * branch (source DetermineBasalBoost.kt, lines 823-867).
 *
 * Receives independently calculated prediction series: this class does not
 * compute BGI, CI, insulin sensitivity, IOB evolution or insulin decisions.
 * Null outputs distinguish disabled legacy branches from a zero prediction.
 */
internal object BoostV1PredictionFinalizer {
    data class Inputs(
        val iob: List<Double>,
        val zeroTemp: List<Double>,
        val cob: List<Double>,
        val uam: List<Double>,
        val mealCob: Double,
        val carbImpact: Double,
        val remainingCarbImpactPeak: Double,
        val enableUam: Boolean,
        val targetBg: Double,
        val eventualBg: Double
    )
    data class Result(
        val iob: List<Int>,
        val zeroTemp: List<Int>,
        val cob: List<Int>?,
        val uam: List<Int>?,
        val lastIobBg: Double,
        val lastCobBg: Double?,
        val lastUamBg: Double?,
        val eventualBg: Double,
        val mayRequestInsulin: Boolean = false
    )

    fun finalize(input: Inputs): Result? {
        if (input.iob.isEmpty() || input.zeroTemp.isEmpty() ||
            !input.targetBg.isFinite() || !input.eventualBg.isFinite() ||
            !input.mealCob.isFinite() || !input.carbImpact.isFinite() ||
            !input.remainingCarbImpactPeak.isFinite() ||
            listOf(input.iob, input.zeroTemp, input.cob, input.uam).any { arr ->
                arr.any { !it.isFinite() }
            }) return null

        fun bounded(values: List<Double>): MutableList<Double> =
            values.map { round(min(401.0, max(39.0, it))) }.toMutableList()

        fun trimRepeated(values: MutableList<Double>, first: Int) {
            for (i in values.size - 1 downTo first) {
                if (values[i - 1] != values[i]) break
                values.removeAt(values.lastIndex)
            }
        }

        val iob = bounded(input.iob)
        trimRepeated(iob, 13)
        val lastIob = round(iob.last()).toDouble()

        val zt = bounded(input.zeroTemp)
        for (i in zt.size - 1 downTo 7) {
            if (zt[i - 1] >= zt[i] || zt[i] <= input.targetBg) break
            zt.removeAt(zt.lastIndex)
        }

        var eventualBg = input.eventualBg
        var cob: List<Int>? = null
        var lastCob: Double? = null
        var uam: List<Int>? = null
        var lastUam: Double? = null
        val carbPredictionsEnabled = input.carbImpact > 0.0 ||
            input.remainingCarbImpactPeak > 0.0

        if (input.mealCob > 0.0 && carbPredictionsEnabled) {
            if (input.cob.isEmpty()) return null
            val roundedCob = bounded(input.cob)
            trimRepeated(roundedCob, 13)
            cob = roundedCob.map { it.toInt() }
            lastCob = roundedCob.last()
            eventualBg = max(eventualBg, round(lastCob))
        }
        if (carbPredictionsEnabled && input.enableUam) {
            if (input.uam.isEmpty()) return null
            val roundedUam = bounded(input.uam)
            trimRepeated(roundedUam, 13)
            uam = roundedUam.map { it.toInt() }
            lastUam = roundedUam.last()
            eventualBg = max(eventualBg, round(lastUam))
        }
        return Result(
            iob = iob.map { it.toInt() },
            zeroTemp = zt.map { it.toInt() },
            cob = cob,
            uam = uam,
            lastIobBg = lastIob,
            lastCobBg = lastCob,
            lastUamBg = lastUam,
            eventualBg = eventualBg
        )
    }
}
