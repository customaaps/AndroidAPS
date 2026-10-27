package app.aaps.plugins.aps.openAPSBoost

import kotlin.math.max
import kotlin.math.pow

/**
 * Read-only source-faithful V1 circadian sensitivity curve, extracted from
 * DetermineBasalBoost.getCircadianSensitivity. No APS, ISF mutation, or pump use.
 */
internal object BoostV1CircadianShadow {
    enum class Finding { READY, INVALID_HOUR }
    data class Result(val finding: Finding, val factor: Double? = null)

    fun inspect(hourOfDay: Int): Result {
        if (hourOfDay !in 0..23) return Result(Finding.INVALID_HOUR)
        val now = max(hourOfDay, 0).toDouble()
        val value = when {
            now >= 0.0 && now < 2.0 -> {
                val n = max(now, 0.5)
                (0.09130 * n.pow(3)) - (0.33261 * n.pow(2)) + 1.4
            }
            now >= 2.0 && now < 3.0 -> (0.0869 * now.pow(3)) - (0.05217 * now.pow(2)) - (0.23478 * now) + 0.8
            now >= 3.0 && now < 8.0 -> (0.0007 * now.pow(3)) - (0.000730 * now.pow(2)) - (0.0007826 * now) + 0.6
            now >= 8.0 && now < 11.0 -> (0.001244 * now.pow(3)) - (0.007619 * now.pow(2)) - (0.007826 * now) + 0.4
            now >= 11.0 && now < 15.0 -> (0.00078 * now.pow(3)) - (0.00272 * now.pow(2)) - (0.07619 * now) + 0.8
            now >= 15.0 && now <= 22.0 -> 1.0
            now > 22.0 && now <= 24.0 -> (0.000125 * now.pow(3)) - (0.0015 * now.pow(2)) - (0.0045 * now) + 1.2
            else -> 1.0
        }
        return Result(Finding.READY, value)
    }
}
