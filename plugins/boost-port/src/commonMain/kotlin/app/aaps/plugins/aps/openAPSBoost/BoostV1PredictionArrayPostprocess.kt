package app.aaps.plugins.aps.openAPSBoost

/** Offline result rendering of the original V1 prediction arrays. No APSResult. */
internal object BoostV1PredictionArrayPostprocess {
    enum class Finding { INVALID_INPUT, READY }
    data class Input(
        val arrays: BoostV1PredictionArraysShadow.Report,
        val targetBg: Double,
        val mealCob: Double,
        val ci: Double,
        val remainingCiPeak: Double,
        val enableUam: Boolean
    )
    data class Report(
        val finding: Finding,
        val iob: List<Int> = emptyList(),
        val zt: List<Int> = emptyList(),
        val cob: List<Int> = emptyList(),
        val uam: List<Int> = emptyList(),
        val aCob: List<Int> = emptyList(),
        val canDeliverInsulin: Boolean = false
    )
    private fun trimFlat(series: List<Int>, minimum: Int): List<Int> {
        val result = series.toMutableList()
        while (result.size > minimum && result[result.lastIndex] == result[result.lastIndex - 1])
            result.removeAt(result.lastIndex)
        return result
    }
    private fun trimZt(series: List<Int>, target: Double): List<Int> {
        val result = series.toMutableList()
        while (result.size > 7 &&
            result[result.lastIndex - 1] < result.last() &&
            result.last() > target) result.removeAt(result.lastIndex)
        return result
    }
    fun inspect(i: Input): Report {
        if (i.arrays.finding != BoostV1PredictionArraysShadow.Finding.READY ||
            !i.targetBg.isFinite() || i.targetBg <= 0.0 ||
            !i.mealCob.isFinite() || i.mealCob < 0.0 ||
            !i.ci.isFinite() || !i.remainingCiPeak.isFinite() ||
            i.remainingCiPeak < 0.0 ||
            i.arrays.iob.isEmpty() || i.arrays.zt.isEmpty())
            return Report(Finding.INVALID_INPUT)
        val withMeal = i.mealCob > 0.0 && (i.ci > 0.0 || i.remainingCiPeak > 0.0)
        val withUam = i.enableUam && (i.ci > 0.0 || i.remainingCiPeak > 0.0)
        return Report(
            Finding.READY,
            iob = trimFlat(i.arrays.iob, 13),
            zt = trimZt(i.arrays.zt, i.targetBg),
            cob = if (withMeal) trimFlat(i.arrays.cob, 13) else emptyList(),
            uam = if (withUam) trimFlat(i.arrays.uam, 13) else emptyList(),
            aCob = if (i.mealCob > 0) trimFlat(i.arrays.aCob, 13) else emptyList()
        )
    }
}
