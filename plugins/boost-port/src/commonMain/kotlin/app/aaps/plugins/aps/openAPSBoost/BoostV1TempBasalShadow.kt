package app.aaps.plugins.aps.openAPSBoost

/**
 * Isolated, immutable representation of archived V1 setTempBasal branches.
 * Results are descriptive labels only; they cannot be dispatched to a pump.
 */
internal object BoostV1TempBasalShadow {
    enum class Finding { INVALID, KEEP_CURRENT, NO_CHANGE, WOULD_CANCEL, WOULD_SET_NEUTRAL, WOULD_SET }
    data class Input(
        val requestedRate: Double,
        val requestedMinutes: Int,
        val currentRate: Double,
        val currentMinutes: Int,
        val profileBasal: Double,
        val skipNeutralTemps: Boolean,
        val limit: BoostV1BasalLimitShadow.Input
    )
    data class Report(
        val finding: Finding,
        val cappedRateForInspection: Double? = null,
        val canDeliverInsulin: Boolean = false
    )
    fun inspect(i: Input): Report {
        val cap = BoostV1BasalLimitShadow.inspect(i.limit)
        val max = cap.diagnosticMaxBasal ?: return Report(Finding.INVALID)
        if (listOf(i.requestedRate, i.currentRate, i.profileBasal).any { !it.isFinite() || it < 0.0 } ||
            i.requestedMinutes < 0 || i.currentMinutes < 0 ||
            i.profileBasal != i.limit.currentBasal) return Report(Finding.INVALID)
        val proposed = i.requestedRate.coerceIn(0.0, max)
        // Legacy engine round_basal is identity; this is a diagnostic comparison.
        if (i.currentMinutes > i.requestedMinutes - 10 &&
            i.currentMinutes <= 120 &&
            proposed <= i.currentRate * 1.2 &&
            proposed >= i.currentRate * 0.8 &&
            i.requestedMinutes > 0) return Report(Finding.KEEP_CURRENT, proposed)
        if (proposed == i.profileBasal) {
            if (i.skipNeutralTemps)
                return Report(if (i.currentMinutes > 0) Finding.WOULD_CANCEL else Finding.NO_CHANGE, proposed)
            return Report(Finding.WOULD_SET_NEUTRAL, proposed)
        }
        return Report(Finding.WOULD_SET, proposed)
    }
}
