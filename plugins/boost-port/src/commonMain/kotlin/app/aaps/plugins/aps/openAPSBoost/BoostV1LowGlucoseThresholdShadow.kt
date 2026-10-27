package app.aaps.plugins.aps.openAPSBoost

/**
 * Replay-only migration of the V1 LGS threshold branch immediately preceding
 * RT initialization (DetermineBasalBoost.kt, source lines 600-617).
 * It does not create basal, SMB or pump instructions.
 */
internal object BoostV1LowGlucoseThresholdShadow {
    enum class Finding { INVALID_INPUT, READY }
    data class Input(
        val minimumTargetMgDl: Double,
        val configuredLgsThresholdMgDl: Double?,
        val deltaAccelerationPercent: Double
    )
    data class Report(
        val finding: Finding,
        val thresholdMgDl: Double? = null,
        val accelerationOverrideApplied: Boolean = false,
        val canDeliverInsulin: Boolean = false
    )
    fun inspect(input: Input): Report {
        if (!input.minimumTargetMgDl.isFinite() ||
            input.minimumTargetMgDl <= 0.0 ||
            !input.deltaAccelerationPercent.isFinite() ||
            (input.configuredLgsThresholdMgDl != null &&
                (!input.configuredLgsThresholdMgDl.isFinite() ||
                    input.configuredLgsThresholdMgDl <= 0.0)))
            return Report(Finding.INVALID_INPUT)
        var threshold = input.minimumTargetMgDl -
            0.5 * (input.minimumTargetMgDl - 40.0)
        val configured = input.configuredLgsThresholdMgDl
        if (configured != null && configured > threshold) threshold = configured
        val override = input.deltaAccelerationPercent > 0.0
        if (override) threshold = 65.0
        return Report(Finding.READY, threshold, override)
    }
}
