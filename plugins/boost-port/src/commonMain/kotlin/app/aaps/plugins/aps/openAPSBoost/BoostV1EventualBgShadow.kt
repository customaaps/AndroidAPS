package app.aaps.plugins.aps.openAPSBoost

import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Read-only extraction of the original V1 BGI/deviation/naive eventual BG
 * calculation. No insulin recommendation, APSResult or pump output.
 */
internal object BoostV1EventualBgShadow {
    enum class Finding { INVALID_INPUT, READY }
    data class Input(
        val bgMgDl: Double,
        val iobUnits: Double,
        val iobActivity: Double,
        val dynamicSens: Double,
        val profileSens: Double,
        val delta: Double,
        val shortAvgDelta: Double,
        val longAvgDelta: Double
    )
    data class Report(
        val finding: Finding,
        val bgi: Double? = null,
        val deviation: Double? = null,
        val naiveEventualBg: Double? = null,
        val eventualBg: Double? = null,
        val canDeliverInsulin: Boolean = false
    )
    fun inspect(i: Input): Report {
        val values = listOf(i.bgMgDl, i.iobUnits, i.iobActivity, i.dynamicSens,
            i.profileSens, i.delta, i.shortAvgDelta, i.longAvgDelta)
        if (values.any { !it.isFinite() } || i.bgMgDl <= 0.0 ||
            i.dynamicSens <= 0.0 || i.profileSens <= 0.0)
            return Report(Finding.INVALID_INPUT)
        val minDelta = min(i.delta, i.shortAvgDelta)
        val minAvgDelta = min(i.shortAvgDelta, i.longAvgDelta)
        val bgi = round(-i.iobActivity * i.dynamicSens * 5.0 * 100.0) / 100.0
        var deviation = round(6.0 * (minDelta - bgi))
        if (deviation < 0.0) {
            deviation = round(6.0 * (minAvgDelta - bgi))
            if (deviation < 0.0) deviation = round(6.0 * (i.longAvgDelta - bgi))
        }
        val naive = round(i.bgMgDl - i.iobUnits *
            (if (i.iobUnits > 0.0) i.dynamicSens else min(i.dynamicSens, i.profileSens)))
        val eventual = naive + deviation
        if (!listOf(bgi, deviation, naive, eventual).all { it.isFinite() })
            return Report(Finding.INVALID_INPUT)
        return Report(Finding.READY, bgi, deviation, naive, eventual)
    }
}
