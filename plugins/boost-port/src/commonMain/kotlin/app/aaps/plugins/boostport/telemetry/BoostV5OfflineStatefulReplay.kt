package app.aaps.plugins.boostport.telemetry

import app.aaps.plugins.aps.openAPSBoostV5.DetermineBasalBoostV5
import app.aaps.plugins.aps.openAPSBoostV5.V5Inputs
import app.aaps.plugins.aps.openAPSBoostV5.V5PersistedState

/**
 * Pure V5 shadow sequence retaining algorithm state *only inside this call*.
 * No APS plugin, preference I/O, pump or insulin command is reachable here.
 * Decisions and resulting state are never exported to callers.
 */
internal object BoostV5OfflineStatefulReplay {
    private const val MAX_CYCLES = 1000
    enum class Finding { EMPTY, TOO_MANY_CYCLES, INVALID_INPUT, ENGINE_FAILED, READY }
    data class Observation(val atMs: Long, val score: Double, val mealState: String)
    data class Report(
        val finding: Finding,
        val observations: List<Observation> = emptyList(),
        val firstInvalidIndex: Int? = null,
        val canDeliverInsulin: Boolean = false,
    )

    fun inspect(cycles: List<V5Inputs>, initial: V5PersistedState = V5PersistedState()): Report {
        if (cycles.isEmpty()) return Report(Finding.EMPTY)
        if (cycles.size > MAX_CYCLES) return Report(Finding.TOO_MANY_CYCLES)
        val engine = DetermineBasalBoostV5()
        var previousAt = 0L
        var state = initial
        val observed = ArrayList<Observation>(cycles.size)
        for ((index, input) in cycles.withIndex()) {
            if (input.nowMs <= previousAt || input.nowMs <= 0L ||
                !input.bg.isFinite() || input.bg < 0.0 ||
                !input.iob.isFinite() || input.iob < 0.0 ||
                !input.maxIob.isFinite() || input.maxIob < 0.0 ||
                !input.baseInsulinReq.isFinite() || input.baseInsulinReq < 0.0 ||
                !input.roundSmbTo.isFinite() || input.roundSmbTo <= 0.0 ||
                input.primerCapU != 0.0 || input.composedFloorActive || input.velocityBudgetActive
            ) return Report(Finding.INVALID_INPUT, firstInvalidIndex = index)
            try {
                val decision = engine.decide(input, state)
                if (!decision.score.isFinite()) return Report(Finding.ENGINE_FAILED, firstInvalidIndex = index)
                observed.add(Observation(input.nowMs, decision.score, decision.mealHypothesis.name))
                state = decision.newPersistedState
                previousAt = input.nowMs
            } catch (_: Exception) {
                return Report(Finding.ENGINE_FAILED, firstInvalidIndex = index)
            }
        }
        return Report(Finding.READY, observed)
    }
}
