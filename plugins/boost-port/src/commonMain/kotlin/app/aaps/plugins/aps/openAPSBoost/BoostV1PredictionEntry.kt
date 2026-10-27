package app.aaps.plugins.aps.openAPSBoost

/**
 * Standalone calculation entry for the source V1 BGI / naive eventual-BG
 * branch. Sensitivity is provided explicitly because V1 dynamic ISF selection
 * is not yet fully migrated. Nothing here computes or dispatches insulin.
 */
internal object BoostV1PredictionEntry {
    enum class Status { INVALID_INPUT, NO_GLUCOSE_DYNAMICS, CGM_EARLY_EXIT, READY }
    data class Result(
        val status: Status,
        val calculated: BoostV1EventualBgShadow.Report? = null,
        val mayDispatchToPump: Boolean = false
    )

    fun calculate(
        invocation: BoostV1DetermineBasalInvocation.Inputs,
        selectedDynamicSensitivityMgDlPerUnit: Double,
        originalProfileSensitivityMgDlPerUnit: Double
    ): Result {
        val gate = BoostV1DetermineBasalReplay.evaluate(invocation)
        if (gate.decision == BoostV1DetermineBasalReplay.Decision.INVALID_INPUT)
            return Result(Status.INVALID_INPUT)
        if (gate.decision == BoostV1DetermineBasalReplay.Decision.CGM_EARLY_EXIT)
            return Result(Status.CGM_EARLY_EXIT)
        val dynamics = invocation.base.glucoseDynamics ?: return Result(Status.NO_GLUCOSE_DYNAMICS)
        val iob = invocation.base.iob.firstOrNull() ?: return Result(Status.INVALID_INPUT)
        val calculated = BoostV1EventualBgShadow.inspect(
            BoostV1EventualBgShadow.Input(
                bgMgDl = invocation.base.glucose.bgMgDl,
                iobUnits = iob.iob,
                iobActivity = iob.activity,
                dynamicSens = selectedDynamicSensitivityMgDlPerUnit,
                profileSens = originalProfileSensitivityMgDlPerUnit,
                delta = dynamics.delta,
                shortAvgDelta = dynamics.shortAvgDelta,
                longAvgDelta = dynamics.longAvgDelta
            )
        )
        return if (calculated.finding == BoostV1EventualBgShadow.Finding.READY)
            Result(Status.READY, calculated)
        else Result(Status.INVALID_INPUT)
    }
}
