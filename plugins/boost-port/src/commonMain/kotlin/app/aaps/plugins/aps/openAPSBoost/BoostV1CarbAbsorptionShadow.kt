package app.aaps.plugins.aps.openAPSBoost

import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** Offline V1 meal absorption precalculation; never connects to APS or pump. */
internal object BoostV1CarbAbsorptionShadow {
    enum class Finding { INVALID, READY }
    data class Input(
        val nowMs: Long,
        val lastCarbMs: Long,
        val carbs: Double,
        val cob: Double,
        val profileSens: Double,
        val effectiveCarbRatio: Double,
        val sensitivityRatio: Double,
        val rawCi: Double,
        val remainingCarbsCap: Int,
        val slopeFromMaxDeviation: Double,
        val slopeFromMinDeviation: Double
    )
    data class Report(
        val finding: Finding,
        val ci: Double? = null,
        val csf: Double? = null,
        val cid: Double? = null,
        val acid: Double? = null,
        val remainingCaHours: Double? = null,
        val remainingCiPeak: Double? = null,
        val slopeFromDeviations: Double? = null,
        val canDeliverInsulin: Boolean = false
    )
    private fun r1(x: Double) = round(x * 10.0) / 10.0
    private fun r2(x: Double) = round(x * 100.0) / 100.0
    fun inspect(i: Input): Report {
        val values = listOf(i.carbs, i.cob, i.profileSens, i.effectiveCarbRatio,
            i.sensitivityRatio, i.rawCi, i.slopeFromMaxDeviation, i.slopeFromMinDeviation)
        if (values.any { !it.isFinite() } || i.nowMs <= 0 ||
            i.carbs < 0 || i.cob < 0 || i.profileSens <= 0 ||
            i.effectiveCarbRatio <= 0 || i.sensitivityRatio <= 0 ||
            i.remainingCarbsCap < 0 || (i.carbs > 0 && i.lastCarbMs <= 0))
            return Report(Finding.INVALID)
        val csf = i.profileSens / i.effectiveCarbRatio
        val maxCi = r1(30.0 * csf * 5.0 / 60.0)
        val ci = min(i.rawCi, maxCi)
        var minimumHours = 3.0 / i.sensitivityRatio
        var hours = minimumHours
        if (i.carbs != 0.0) {
            minimumHours = max(minimumHours, i.cob / 20.0)
            val carbAgeMinutes = round((i.nowMs - i.lastCarbMs) / 60000.0)
            hours = r1(minimumHours + 1.5 * carbAgeMinutes / 60.0)
        }
        if (!hours.isFinite() || hours <= 0.0) return Report(Finding.INVALID)
        val totalCi = max(0.0, ci * 12.0 * hours / 2.0)
        val remaining = min(min(90, i.remainingCarbsCap).toDouble(), max(0.0, i.cob - totalCi / csf))
        val peak = remaining * csf / (12.0 * hours / 2.0)
        val cid = if (ci == 0.0) 0.0 else min(hours * 6.0, max(0.0, i.cob * csf / ci))
        val acid = max(0.0, i.cob * csf / 10.0)
        val slope = min(r2(i.slopeFromMaxDeviation), -r2(i.slopeFromMinDeviation) / 3.0)
        if (listOf(ci, csf, cid, acid, hours, peak, slope).any { !it.isFinite() })
            return Report(Finding.INVALID)
        return Report(Finding.READY, ci, csf, cid, acid, hours, peak, slope)
    }
}
