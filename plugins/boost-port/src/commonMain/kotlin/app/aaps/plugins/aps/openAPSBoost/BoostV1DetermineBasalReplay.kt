package app.aaps.plugins.aps.openAPSBoost

/**
 * Pure, replay-only entry gate for the archived V1 determine_basal inputs.
 * This executes the already-migrated CGM entry branch and rejects incomplete
 * invocation snapshots; it never calculates or requests insulin.
 */
internal object BoostV1DetermineBasalReplay {
    enum class Decision {
        INVALID_INPUT, CGM_EARLY_EXIT, READY_FOR_REFERENCE_EXECUTION
    }
    data class Evaluation(
        val decision: Decision,
        val invocationFinding: BoostV1DetermineBasalInvocation.Finding,
        val cgmFinding: BoostV1CgmGateShadow.Finding,
        val currentIob: Double?,
        val ageMinutes: Double?,
        val isReferenceCalculation: Boolean = false,
        val mayDoseInsulin: Boolean = false
    )

    fun evaluate(input: BoostV1DetermineBasalInvocation.Inputs): Evaluation {
        val invocation = BoostV1DetermineBasalInvocation.inspect(input)
        val glucose = BoostV1CgmGateShadow.inspect(input.base.glucose)
        if (invocation.finding != BoostV1DetermineBasalInvocation.Finding.READY_FOR_OFFLINE_REPLAY)
            return Evaluation(
                Decision.INVALID_INPUT, invocation.finding, glucose.finding,
                null, glucose.ageMinutes
            )
        if (glucose.wouldEnterV1EarlyExit)
            return Evaluation(
                Decision.CGM_EARLY_EXIT, invocation.finding, glucose.finding,
                input.base.iob.firstOrNull()?.iob, glucose.ageMinutes
            )
        return Evaluation(
            Decision.READY_FOR_REFERENCE_EXECUTION,
            invocation.finding, glucose.finding,
            input.base.iob.firstOrNull()?.iob, glucose.ageMinutes
        )
    }
}
