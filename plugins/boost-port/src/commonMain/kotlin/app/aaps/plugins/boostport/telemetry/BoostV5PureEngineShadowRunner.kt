package app.aaps.plugins.boostport.telemetry

import app.aaps.plugins.aps.openAPSBoostV5.DetermineBasalBoostV5
import app.aaps.plugins.aps.openAPSBoostV5.V5Inputs
import app.aaps.plugins.aps.openAPSBoostV5.V5PersistedState

/**
 * Explicit invocation of the migrated *pure* V5 calculation in an isolated
 * inspection surface. Never returns a V5Decision, insulin dose or delivery
 * instruction to callers and is NOT registered as an APS component.
 *
 * No references to pump, APSResult, SharedPreferences, database or app service.
 */
internal object BoostV5PureEngineShadowRunner {
    enum class Finding { INPUTS_REJECTED, PRESET_UNSAFE, ENGINE_EXCEPTION, CALCULATED_OFFLINE }
    data class Report(
        val finding: Finding,
        val score: Double? = null,
        val stateLabel: String? = null,
        val canDeliverInsulin: Boolean = false
    )

    fun inspect(inputs: V5Inputs, prior: V5PersistedState): Report {
        // Disable opt-in floors and primers, which can add insulin to a candidate.
        // Reject rather than silently mutate caller input.
        if (inputs.primerCapU != 0.0 || inputs.composedFloorActive ||
            inputs.velocityBudgetActive
        ) return Report(Finding.PRESET_UNSAFE)
        if (inputs.nowMs <= 0L || !inputs.bg.isFinite() ||
            !inputs.baseInsulinReq.isFinite() || !inputs.iob.isFinite() ||
            !inputs.maxIob.isFinite() || inputs.baseInsulinReq < 0.0 ||
            inputs.maxIob < 0.0 || inputs.iob < 0.0
        ) return Report(Finding.INPUTS_REJECTED)
        return try {
            val decision = DetermineBasalBoostV5().decide(inputs, prior)
            if (!decision.score.isFinite()) Report(Finding.ENGINE_EXCEPTION)
            else Report(
                finding = Finding.CALCULATED_OFFLINE,
                score = decision.score,
                stateLabel = decision.mealHypothesis.name
            )
        } catch (_: Exception) {
            Report(Finding.ENGINE_EXCEPTION)
        }
    }
}
