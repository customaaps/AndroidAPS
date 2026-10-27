package app.aaps.plugins.aps.openAPSBoost

/**
 * Non-actuating, ordered entry preflight for the archived V1 determine_basal.
 * This layer is deliberately a prerequisite to replay, not a dosing algorithm.
 * The source engine's later prediction, safety and SMB branches are not called.
 */
internal object BoostV1DetermineBasalPreflight {
    enum class State {
        INVALID_INPUT, UNRELIABLE_CGM, STALE_CGM, FUTURE_CGM, FLAT_CGM,
        READY_FOR_REPLAY
    }
    data class Result(
        val state: State,
        val inputValidation: BoostV1ApsTypeBoundary.Validation,
        val glucoseAgeMinutes: Double?,
        val iobPointCount: Int,
        val mayCalculateDose: Boolean = false,
        val mayDispatchToPump: Boolean = false
    )

    fun inspect(input: BoostV1ApsTypeBoundary.Inputs): Result {
        val validity = BoostV1ApsTypeBoundary.validate(input)
        if (validity != BoostV1ApsTypeBoundary.Validation.VALID) {
            return Result(State.INVALID_INPUT, validity, null, input.iob.size)
        }
        val cgm = BoostV1CgmGateShadow.inspect(input.glucose)
        val state = when (cgm.finding) {
            BoostV1CgmGateShadow.Finding.INVALID_INPUT -> State.INVALID_INPUT
            BoostV1CgmGateShadow.Finding.CGM_UNRELIABLE -> State.UNRELIABLE_CGM
            BoostV1CgmGateShadow.Finding.STALE -> State.STALE_CGM
            BoostV1CgmGateShadow.Finding.FUTURE -> State.FUTURE_CGM
            BoostV1CgmGateShadow.Finding.FLAT -> State.FLAT_CGM
            BoostV1CgmGateShadow.Finding.USABLE -> State.READY_FOR_REPLAY
        }
        return Result(state, validity, cgm.ageMinutes, input.iob.size)
    }
}
