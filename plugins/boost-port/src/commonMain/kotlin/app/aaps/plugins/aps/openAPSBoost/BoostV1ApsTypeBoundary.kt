package app.aaps.plugins.aps.openAPSBoost

/**
 * Immutable, non-actuating boundary for the archived V1 input types:
 * RT, OapsProfileBoost and Array<IobTotal>.
 *
 * This representation deliberately does not implement AndroidAPS APSResult
 * and cannot be sent to the pump. A platform adapter can copy validated
 * measurements here for offline comparison without retaining mutable AAPS
 * objects or silently converting missing input to zero.
 */
internal object BoostV1ApsTypeBoundary {
    data class Profile(
        val currentBasal: Double,
        val maxBasal: Double,
        val maxDailyBasal: Double,
        val maxDailySafetyMultiplier: Double,
        val currentBasalSafetyMultiplier: Double,
        val skipNeutralTemps: Boolean
    ) {
        fun basalLimitInput() = BoostV1BasalLimitShadow.Input(
            maxBasal = maxBasal,
            maxDailySafetyMultiplier = maxDailySafetyMultiplier,
            maxDailyBasal = maxDailyBasal,
            currentBasalSafetyMultiplier = currentBasalSafetyMultiplier,
            currentBasal = currentBasal
        )
    }

    data class IobPoint(
        val timeMs: Long,
        val iob: Double,
        val activity: Double
    )

    /** Full glucose dynamics required by the legacy V1 prediction branches. */
    data class GlucoseDynamics(
        val delta: Double,
        val shortAvgDelta: Double,
        val longAvgDelta: Double,
        val bgAcceleration: Double? = null
    )

    data class Inputs(
        val glucose: BoostV1CgmGateShadow.Input,
        val currentTempRate: Double,
        val currentTempMinutes: Int,
        val profile: Profile,
        val iob: List<IobPoint>,
        val glucoseDynamics: GlucoseDynamics? = null
    )

    enum class Validation {
        VALID, INVALID_GLUCOSE, INVALID_PROFILE, INVALID_CURRENT_TEMP,
        MISSING_IOB, INVALID_IOB, OUT_OF_ORDER_IOB
    }

    fun validate(input: Inputs): Validation {
        if (BoostV1CgmGateShadow.inspect(input.glucose).finding ==
            BoostV1CgmGateShadow.Finding.INVALID_INPUT)
            return Validation.INVALID_GLUCOSE
        if (BoostV1BasalLimitShadow.inspect(input.profile.basalLimitInput())
                .finding != BoostV1BasalLimitShadow.Finding.READY)
            return Validation.INVALID_PROFILE
        if (!input.currentTempRate.isFinite() || input.currentTempRate < 0.0 ||
            input.currentTempMinutes < 0)
            return Validation.INVALID_CURRENT_TEMP
        if (input.glucoseDynamics != null && listOf(
                input.glucoseDynamics.delta,
                input.glucoseDynamics.shortAvgDelta,
                input.glucoseDynamics.longAvgDelta
            ).any { !it.isFinite() } ||
            input.glucoseDynamics?.bgAcceleration?.isFinite() == false) return Validation.INVALID_GLUCOSE
        if (input.iob.isEmpty()) return Validation.MISSING_IOB
        if (input.iob.any {
                it.timeMs <= 0 || !it.iob.isFinite() || !it.activity.isFinite()
            }) return Validation.INVALID_IOB
        // Historical AAPS IOB frames can arrive chronologically or in reverse.
        // Keep original order (the engine uses indexed points); never sort silently.
        val times = input.iob.map { it.timeMs }
        if (times.zipWithNext().any { (a, b) -> a == b } ||
            !(times.zipWithNext().all { (a, b) -> a < b } ||
                times.zipWithNext().all { (a, b) -> a > b }))
            return Validation.OUT_OF_ORDER_IOB
        return Validation.VALID
    }

    /**
     * Bridge output is comparison-only. Neither the returned object nor any
     * nested value is compatible with pump dispatch or actionable APSResult.
     */
    data class Comparison(
        val validation: Validation,
        val result: BoostV1ResultBridge.Snapshot?,
        val eligibleForPumpDispatch: Boolean = false
    )

    fun inspect(input: Inputs, requestedRate: Double, requestedMinutes: Int): Comparison {
        val validation = validate(input)
        if (validation != Validation.VALID) return Comparison(validation, null)
        val temp = BoostV1TempBasalShadow.Input(
            requestedRate = requestedRate,
            requestedMinutes = requestedMinutes,
            currentRate = input.currentTempRate,
            currentMinutes = input.currentTempMinutes,
            profileBasal = input.profile.currentBasal,
            skipNeutralTemps = input.profile.skipNeutralTemps,
            limit = input.profile.basalLimitInput()
        )
        return Comparison(Validation.VALID, BoostV1ResultBridge.inspect(input.glucose, temp))
    }
}
