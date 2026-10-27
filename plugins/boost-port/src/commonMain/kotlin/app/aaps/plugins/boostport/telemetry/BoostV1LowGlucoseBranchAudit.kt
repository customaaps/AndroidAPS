package app.aaps.plugins.boostport.telemetry

/**
 * Diagnostic classification of the initial low-eventual-BG branch in original
 * V1 determine_basal. No insulin command or executable APSResult is emitted.
 */
internal object BoostV1LowGlucoseBranchAudit {
    enum class Finding { INVALID, NOT_LOW, LOW_EVENTUAL, NAIVE_CRITICAL_LOW }
    data class Input(
        val eventualBg: Double,
        val minTargetBg: Double,
        val naiveEventualBg: Double,
        val minDelta: Double,
        val expectedDelta: Double,
        val carbsRequired: Double
    )
    data class Report(
        val finding: Finding,
        val wouldEnterZeroTempBranch: Boolean = false,
        val canDeliverInsulin: Boolean = false
    )
    fun inspect(i: Input): Report {
        if (listOf(i.eventualBg, i.minTargetBg, i.naiveEventualBg,
                i.minDelta, i.expectedDelta, i.carbsRequired).any { !it.isFinite() } ||
            i.minTargetBg <= 0.0 || i.carbsRequired < 0.0) return Report(Finding.INVALID)
        if (i.eventualBg >= i.minTargetBg) return Report(Finding.NOT_LOW)
        val critical = i.minDelta > i.expectedDelta && i.minDelta > 0.0 &&
            i.carbsRequired == 0.0 && i.naiveEventualBg < 40.0
        return Report(
            if (critical) Finding.NAIVE_CRITICAL_LOW else Finding.LOW_EVENTUAL,
            wouldEnterZeroTempBranch = critical
        )
    }
}
