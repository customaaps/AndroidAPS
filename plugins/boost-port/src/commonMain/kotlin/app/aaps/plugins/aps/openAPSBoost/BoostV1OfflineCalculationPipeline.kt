package app.aaps.plugins.aps.openAPSBoost

/**
 * One non-actuating execution boundary combining the migrated V1 sensitivity,
 * 5-minute prediction and SMB-condition branches. Inputs that cannot be
 * reconstructed from the partial snapshot MUST be supplied independently
 * from the same reference cycle. Never returns an RT or treatment request.
 */
internal object BoostV1OfflineCalculationPipeline {
    enum class Finding {
        INVALID_INVOCATION, EARLY_CGM_EXIT, INVALID_SENSITIVITY,
        INVALID_PREDICTIONS, INVALID_SMB_CHECK, INVALID_LGS_THRESHOLD, INCONSISTENT_CYCLE_INPUTS, READY_FOR_REFERENCE_COMPARISON
    }

    data class Inputs(
        val invocation: BoostV1DetermineBasalInvocation.Inputs,
        val selectedSensitivity: BoostV1IsfShadowCalculation.Input,
        val prediction: BoostV1PredictionArraysShadow.Input,
        val smbEligibility: BoostV1SmbEligibilityShadow.Input,
        val lgsThreshold: BoostV1LowGlucoseThresholdShadow.Input? = null
    )

    data class Report(
        val finding: Finding,
        val dynamicIsf: Double? = null,
        val predictionIob: List<Int> = emptyList(),
        val predictionCob: List<Int> = emptyList(),
        val predictionZt: List<Int> = emptyList(),
        val predictionUam: List<Int> = emptyList(),
        val smbConditionMatched: Boolean = false,
        val lgsThresholdMgDl: Double? = null,
        val lgsAccelerationOverrideApplied: Boolean = false,
        val isOriginalDetermineBasalResult: Boolean = false,
        val canDeliverInsulin: Boolean = false
    )

    fun inspect(input: Inputs): Report {
        val gate = BoostV1DetermineBasalReplay.evaluate(input.invocation)
        when (gate.decision) {
            BoostV1DetermineBasalReplay.Decision.INVALID_INPUT ->
                return Report(Finding.INVALID_INVOCATION)
            BoostV1DetermineBasalReplay.Decision.CGM_EARLY_EXIT ->
                return Report(Finding.EARLY_CGM_EXIT)
            BoostV1DetermineBasalReplay.Decision.READY_FOR_REFERENCE_EXECUTION -> Unit
        }
        // Refuse to compare independently supplied forecast/SMB inputs from
        // contradictory snapshots. This is not proof of full provenance.
        val base = input.invocation.base
        val dynamics = base.glucoseDynamics
        if (input.prediction.bg != base.glucose.bgMgDl ||
            input.smbEligibility.microBolusAllowed != input.invocation.microBolusAllowed ||
            input.smbEligibility.cobGrams != input.invocation.meal.mealCob ||
            input.smbEligibility.carbsGrams != input.invocation.meal.carbs ||
            (dynamics != null && !dynamics.delta.isFinite()))
            return Report(Finding.INCONSISTENT_CYCLE_INPUTS)
        val isf = BoostV1IsfShadowCalculation.inspect(input.selectedSensitivity)
        if (isf.finding != BoostV1IsfShadowCalculation.Finding.READY ||
            isf.shadowIsf == null) return Report(Finding.INVALID_SENSITIVITY)
        val forecast = BoostV1PredictionArraysShadow.inspect(input.prediction)
        if (forecast.finding != BoostV1PredictionArraysShadow.Finding.READY)
            return Report(Finding.INVALID_PREDICTIONS)
        val smb = BoostV1SmbEligibilityShadow.inspect(input.smbEligibility)
        if (smb.finding == BoostV1SmbEligibilityShadow.Finding.INVALID_INPUT)
            return Report(Finding.INVALID_SMB_CHECK)

        val lgs = input.lgsThreshold?.let { BoostV1LowGlucoseThresholdShadow.inspect(it) }
        if (lgs?.finding == BoostV1LowGlucoseThresholdShadow.Finding.INVALID_INPUT)
            return Report(Finding.INVALID_LGS_THRESHOLD)

        // These are independent calculations, not a source-equivalent sequence:
        // no automatic handoff from ISF to forecasts or forecasts to dosing.
        return Report(
            finding = Finding.READY_FOR_REFERENCE_COMPARISON,
            dynamicIsf = isf.shadowIsf,
            lgsThresholdMgDl = lgs?.thresholdMgDl,
            lgsAccelerationOverrideApplied = lgs?.accelerationOverrideApplied ?: false,
            predictionIob = forecast.iob,
            predictionCob = forecast.cob,
            predictionZt = forecast.zt,
            predictionUam = forecast.uam,
            smbConditionMatched =
                smb.finding == BoostV1SmbEligibilityShadow.Finding.CONDITION_MATCHED
        )
    }
}
