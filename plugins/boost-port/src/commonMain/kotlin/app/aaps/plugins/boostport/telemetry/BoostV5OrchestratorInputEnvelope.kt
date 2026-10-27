package app.aaps.plugins.boostport.telemetry

/**
 * Inert copy of the ORIGINAL V5Inputs data contract for source parity review.
 * Deliberately NOT named V5Inputs, not wired into any APS implementation, and
 * NOT an insulin calculation. Projected-IOB ML callbacks are not executable here.
 */
internal object BoostV5OrchestratorInputEnvelope {
    data class Cycle(
        val delta: Double, val shortAvgDelta: Double, val deltaAccl: Double,
        val bg: Double, val eventualBg: Double, val targetBg: Double,
        val maxDelta: Double, val minGuardBg: Double, val minGuardThreshold: Double,
        val deltaHistory: List<Double>,
        val iob: Double, val maxIob: Double, val baseInsulinReq: Double,
        val roundSmbTo: Double, val enableSmbPreChecks: Boolean,
        val mlHypoRisk: Double?, val mlMealLikely: Double?,
        /** Original callback is represented by presence only, never invoked. */
        val projectedIobRiskCallbackAvailable: Boolean = false,
        val recentLowBg: Double, val cumulativeRise30min: Double, val hour: Int,
        val exerciseActive: Boolean, val inPostExerciseWindow: Boolean,
        val asleep: Boolean = false, val fastCarbConfirmEnabled: Boolean = false,
        val aggressiveEarlyConfirmEnabled: Boolean = false,
        val sensorQualityOk: Boolean = true, val postRescueWindow: Boolean = false,
        val v1WouldDoseU: Double? = null, val composedFloorActive: Boolean = false,
        val velocityBudgetActive: Boolean = false,
        val profileSwitched: Boolean = false, val pumpDisconnected: Boolean = false,
        val loopSuspended: Boolean = false, val timeJumpMinutes: Double = 0.0,
        val aggressionUserKnob: Double = 1.0, val hypoCautionUserKnob: Double = 1.0,
        val sensitivityUserKnob: Double = 1.0,
        val confirmedCapU: Double, val committedCapU: Double,
        val primerCapU: Double = 0.0, val primerUseTempBasal: Boolean = false,
        val nowMs: Long = 0L, val mealAnnounced: Boolean = false,
    )

    enum class Finding { STRUCTURALLY_VALID, INVALID_NUMERIC, INVALID_CLOCK, INVALID_HOUR, EMPTY_DELTA_HISTORY }
    data class Report(val finding: Finding, val liveDosingPermitted: Boolean = false)

    fun inspect(input: Cycle): Report {
        val values = listOf(
            input.delta, input.shortAvgDelta, input.deltaAccl, input.bg,
            input.eventualBg, input.targetBg, input.maxDelta,
            input.minGuardBg, input.minGuardThreshold, input.iob,
            input.maxIob, input.baseInsulinReq, input.roundSmbTo,
            input.recentLowBg, input.cumulativeRise30min, input.timeJumpMinutes,
            input.aggressionUserKnob, input.hypoCautionUserKnob,
            input.sensitivityUserKnob, input.confirmedCapU, input.committedCapU,
            input.primerCapU,
        ) + listOfNotNull(input.mlHypoRisk, input.mlMealLikely, input.v1WouldDoseU) +
            input.deltaHistory
        return when {
            values.any { !it.isFinite() } -> Report(Finding.INVALID_NUMERIC)
            input.nowMs < 0L -> Report(Finding.INVALID_CLOCK)
            input.hour !in 0..23 -> Report(Finding.INVALID_HOUR)
            input.deltaHistory.isEmpty() -> Report(Finding.EMPTY_DELTA_HISTORY)
            else -> Report(Finding.STRUCTURALLY_VALID)
        }
    }
}
