package app.aaps.plugins.aps.openAPSBoostV5

/**
 * Offline replacement for the read-only telemetry portion of
 * OpenAPSBoostV5Plugin.runShadow(). No RT mutation, APS plugin, preferences,
 * pump callback or insulin delivery. Caller owns the V5Decision.
 *
 * Quantities include insulin-related values solely for historical comparison.
 */
internal object V5ShadowTelemetryOffline {
    data class Cycle(
        val score: Double,
        val state: String,
        val age: Int,
        val budget: Double,
        val actionMultiplier: Double,
        val finalDoseU: Double,
        val velocityFactor: Double,
        val doseAfterCapsU: Double,
        val doseAfterBrakesU: Double,
        val confirmGate: String,
        val prospectiveConfirmShotU: Double,
        val floorWouldAddU: Double?,
        val velocityBudgetWouldAddU: Double?,
        val mealSessionStarted: Boolean,
        val activeDosingEnabled: Boolean = false,
    )

    fun inspect(decision: V5Decision): Cycle = Cycle(
        score = decision.score,
        state = decision.mealHypothesis.name,
        age = decision.mealHypothesisAge,
        budget = decision.aggressionBudget.budget,
        actionMultiplier = decision.actionMultiplier,
        finalDoseU = decision.finalDose,
        velocityFactor = decision.velocityFactor,
        doseAfterCapsU = decision.insulinToDeliver,
        doseAfterBrakesU = decision.phase3.finalDose,
        confirmGate = decision.confirmGate,
        prospectiveConfirmShotU = decision.prospectiveConfirmShot,
        floorWouldAddU = decision.floorWouldAdd,
        velocityBudgetWouldAddU = decision.velocityBudgetWouldAdd,
        mealSessionStarted = decision.mealSessionStarted,
    )
}
