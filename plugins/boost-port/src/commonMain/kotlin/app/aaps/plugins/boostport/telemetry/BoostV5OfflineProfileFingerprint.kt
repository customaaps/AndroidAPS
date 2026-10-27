package app.aaps.plugins.boostport.telemetry

/**
 * Offline comparison of prepared profile inputs between replay frames.
 * Exact structural equality is intentional: no rounding, dose calculation,
 * preference writes, APS invocation, or pump interfaces.
 */
internal object BoostV5OfflineProfileFingerprint {
    enum class Finding { UNAVAILABLE, UNCHANGED, CHANGED }
    data class Report(
        val finding: Finding,
        val changedBase: Boolean,
        val changedAdditional: Boolean,
    )

    fun compare(
        previous: BoostV5OfflineProfileAssembly.Report,
        current: BoostV5OfflineProfileAssembly.Report,
    ): Report {
        val first = previous.snapshot ?: return Report(Finding.UNAVAILABLE, false, false)
        val second = current.snapshot ?: return Report(Finding.UNAVAILABLE, false, false)
        val baseChanged = first.base != second.base
        val additionalChanged = first.additional != second.additional
        return Report(
            if (baseChanged || additionalChanged) Finding.CHANGED else Finding.UNCHANGED,
            baseChanged,
            additionalChanged,
        )
    }
}
