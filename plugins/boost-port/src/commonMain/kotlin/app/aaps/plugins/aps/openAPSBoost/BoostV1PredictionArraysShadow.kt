package app.aaps.plugins.aps.openAPSBoost

import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Offline extraction of V1's 5-minute IOB/COB/aCOB/ZT/UAM prediction loop.
 * Meal-absorption parameters are explicitly supplied; no APS or pump output.
 */
internal object BoostV1PredictionArraysShadow {
    enum class Finding { INVALID_INPUT, INVALID_TICK, READY }
    data class Tick(val activity: Double, val zeroTempActivity: Double?)
    data class Input(
        val bg: Double,
        val sens: Double,
        val ci: Double,
        val uci: Double,
        val cid: Double,
        val acid: Double,
        val remainingCATimeHours: Double,
        val remainingCIpeak: Double,
        val slopeFromDeviations: Double,
        val insulinDivisor: Double,
        val normalTarget: Double,
        val sensNormalTarget: Double,
        val velocity: Double,
        val bgCap: Double,
        val ticks: List<Tick>
    )
    data class Report(
        val finding: Finding,
        val iob: List<Int> = emptyList(),
        val cob: List<Int> = emptyList(),
        val aCob: List<Int> = emptyList(),
        val zt: List<Int> = emptyList(),
        val uam: List<Int> = emptyList(),
        val failedTick: Int? = null,
        val canDeliverInsulin: Boolean = false
    )
    private fun round2(x: Double) = round(x * 100.0) / 100.0
    private fun isf(bg: Double, i: Input): Double? =
        BoostV1IsfShadowCalculation.inspect(
            BoostV1IsfShadowCalculation.Input(
                max(bg, 39.0), true, i.bgCap, i.insulinDivisor,
                i.normalTarget, i.sensNormalTarget, i.velocity
            )
        ).shadowIsf

    private fun export(series: List<Double>): List<Int> =
        series.map { round(it.coerceIn(39.0, 401.0)).toInt() }

    fun inspect(i: Input): Report {
        val params = listOf(i.bg, i.sens, i.ci, i.uci, i.cid, i.acid,
            i.remainingCATimeHours, i.remainingCIpeak, i.slopeFromDeviations,
            i.insulinDivisor, i.normalTarget, i.sensNormalTarget, i.velocity, i.bgCap)
        if (params.any { !it.isFinite() } || i.bg <= 0.0 || i.sens <= 0.0 ||
            i.cid < 0.0 || i.acid < 0.0 || i.remainingCATimeHours <= 0.0 ||
            i.remainingCIpeak < 0.0 || i.ticks.isEmpty() || i.ticks.size > 288)
            return Report(Finding.INVALID_INPUT)
        val iob = mutableListOf(i.bg)
        val cob = mutableListOf(i.bg)
        val aCob = mutableListOf(i.bg)
        val zt = mutableListOf(i.bg)
        val uam = mutableListOf(i.bg)
        for ((index, tick) in i.ticks.withIndex()) {
            if (!tick.activity.isFinite() || tick.zeroTempActivity?.isFinite() != true)
                return Report(Finding.INVALID_TICK, failedTick = index)
            // Source loop evolves from the last appended point even beyond the 48 cap.
            val predBgi = round2(-tick.activity * i.sens * 5.0)
            val iobIsf = isf(iob.last(), i) ?: return Report(Finding.INVALID_TICK, failedTick = index)
            val ztIsf = isf(zt.last(), i) ?: return Report(Finding.INVALID_TICK, failedTick = index)
            val uamIsf = isf(uam.last(), i) ?: return Report(Finding.INVALID_TICK, failedTick = index)
            val iobBgi = round2(-tick.activity * iobIsf * 5.0)
            val ztBgi = round2(-tick.zeroTempActivity * ztIsf * 5.0)
            val uamBgi = round2(-tick.activity * uamIsf * 5.0)
            val predDev = i.ci * (1.0 - min(1.0, iob.size / 12.0))
            val predCi = max(0.0, max(0.0, i.ci) * (1.0 - cob.size / max(i.cid * 2.0, 1.0)))
            val predAci = max(0.0, 10.0 * (1.0 - cob.size / max(i.acid * 2.0, 1.0)))
            val intervals = min(cob.size.toDouble(), i.remainingCATimeHours * 12.0 - cob.size)
            val remainingCi = max(0.0, intervals / (i.remainingCATimeHours * 6.0) * i.remainingCIpeak)
            val predUci = min(
                max(0.0, i.uci + uam.size * i.slopeFromDeviations),
                max(0.0, i.uci * (1.0 - uam.size / 36.0))
            )
            val nextIob = iob.last() + iobBgi + predDev
            val nextCob = cob.last() + predBgi + min(0.0, predDev) + predCi + remainingCi
            val nextACob = aCob.last() + predBgi + min(0.0, predDev) + predAci
            val nextZt = zt.last() + ztBgi
            val nextUam = uam.last() + uamBgi + min(0.0, predDev) + predUci
            if (!listOf(nextIob, nextCob, nextACob, nextZt, nextUam).all { it.isFinite() })
                return Report(Finding.INVALID_TICK, failedTick = index)
            if (iob.size < 48) iob.add(nextIob)
            if (cob.size < 48) cob.add(nextCob)
            if (aCob.size < 48) aCob.add(nextACob)
            if (zt.size < 48) zt.add(nextZt)
            if (uam.size < 48) uam.add(nextUam)
        }
        return Report(Finding.READY, export(iob), export(cob), export(aCob), export(zt), export(uam))
    }
}
