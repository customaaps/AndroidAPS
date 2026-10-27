package app.aaps.plugins.aps.openAPSBoost

import kotlin.math.min

/**
 * Isolated extraction of the legacy V1 getMaxSafeBasal bound.
 * This returns a diagnostic cap, NEVER a requested temp basal or pump command.
 */
internal object BoostV1BasalLimitShadow {
    enum class Finding { INVALID_INPUT, READY }
    data class Input(
        val maxBasal: Double,
        val maxDailySafetyMultiplier: Double,
        val maxDailyBasal: Double,
        val currentBasalSafetyMultiplier: Double,
        val currentBasal: Double
    )
    data class Report(
        val finding: Finding,
        val diagnosticMaxBasal: Double? = null,
        val canDeliverInsulin: Boolean = false
    )
    fun inspect(i: Input): Report {
        val values = listOf(i.maxBasal, i.maxDailySafetyMultiplier, i.maxDailyBasal,
            i.currentBasalSafetyMultiplier, i.currentBasal)
        if (values.any { !it.isFinite() || it < 0.0 }) return Report(Finding.INVALID_INPUT)
        val result = min(i.maxBasal, min(i.maxDailySafetyMultiplier * i.maxDailyBasal,
            i.currentBasalSafetyMultiplier * i.currentBasal))
        return if (result.isFinite()) Report(Finding.READY, result)
        else Report(Finding.INVALID_INPUT)
    }
}
