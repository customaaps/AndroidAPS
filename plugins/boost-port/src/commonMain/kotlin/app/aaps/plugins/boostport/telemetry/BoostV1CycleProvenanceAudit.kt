package app.aaps.plugins.boostport.telemetry

import app.aaps.plugins.aps.openAPSBoost.BoostV1CgmGateShadow

/**
 * Same-cycle provenance audit for offline V1 prediction / protection inputs.
 * Does not evaluate or output an insulin treatment decision.
 */
internal object BoostV1CycleProvenanceAudit {
    enum class Finding { INVALID_CLOCK, INCONSISTENT_GLUCOSE, INCONSISTENT_MEAL, INCONSISTENT_TARGET, INVALID_FORECAST, READY }
    data class Input(
        val atMs: Long,
        val cgm: BoostV1CgmGateShadow.Input,
        val prediction: BoostV1ForecastPipeline.Input,
        val minTargetMgDl: Double,
        val lowBranch: BoostV1LowGlucoseBranchAudit.Input
    )
    data class Report(
        val finding: Finding,
        val canDeliverInsulin: Boolean = false
    )

    fun inspect(i: Input): Report {
        if (i.atMs <= 0L || i.cgm.currentTimeMs != i.atMs ||
            i.prediction.meal.nowMs != i.atMs ||
            i.cgm.glucoseTimeMs > i.atMs || i.cgm.glucoseTimeMs <= 0L)
            return Report(Finding.INVALID_CLOCK)
        if (!i.cgm.bgMgDl.isFinite() || i.cgm.bgMgDl != i.prediction.forecast.bg)
            return Report(Finding.INCONSISTENT_GLUCOSE)
        if (i.prediction.meal.cob < 0.0 || !i.prediction.meal.cob.isFinite() ||
            i.prediction.meal.carbs < 0.0 || !i.prediction.meal.carbs.isFinite())
            return Report(Finding.INCONSISTENT_MEAL)
        if (!i.minTargetMgDl.isFinite() || i.minTargetMgDl <= 0.0 ||
            i.lowBranch.minTargetBg != i.minTargetMgDl)
            return Report(Finding.INCONSISTENT_TARGET)
        // A structurally consistent snapshot may still contain unusable CGM data.
        // Reject it before running the more expensive forecast pipeline.
        if (BoostV1CgmGateShadow.inspect(i.cgm).finding !=
            BoostV1CgmGateShadow.Finding.USABLE)
            return Report(Finding.INCONSISTENT_GLUCOSE)
        val forecast = BoostV1ForecastPipeline.inspect(i.prediction)
        if (forecast.finding != BoostV1ForecastPipeline.Finding.READY)
            return Report(Finding.INVALID_FORECAST)
        if (BoostV1LowGlucoseBranchAudit.inspect(i.lowBranch).finding ==
            BoostV1LowGlucoseBranchAudit.Finding.INVALID)
            return Report(Finding.INVALID_FORECAST)
        return Report(Finding.READY)
    }
}
