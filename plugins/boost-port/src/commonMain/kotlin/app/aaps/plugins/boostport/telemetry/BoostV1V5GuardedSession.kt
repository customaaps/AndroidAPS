package app.aaps.plugins.boostport.telemetry

import app.aaps.plugins.aps.openAPSBoostV5.V5Inputs
import app.aaps.plugins.aps.openAPSBoostV5.V5PersistedState

/**
 * Cross-version offline diagnostic session. V1 prognosis provenance must pass
 * before any V5 shadow replay. Neither engine is registered for therapy.
 */
internal object BoostV1V5GuardedSession {
    enum class Finding { EMPTY, TOO_MANY, INVALID_CLOCK, INVALID_V1, INVALID_V5, READY }
    data class Cycle(
        val atMs: Long,
        val v1: BoostV1CycleProvenanceAudit.Input,
        val v5: V5Inputs
    )
    data class Observation(val atMs: Long, val v5Score: Double, val mealState: String)
    data class Report(
        val finding: Finding,
        val observations: List<Observation> = emptyList(),
        val failedIndex: Int? = null,
        val canDeliverInsulin: Boolean = false
    )
    fun inspect(cycles: List<Cycle>, initialState: V5PersistedState = V5PersistedState()): Report {
        if (cycles.isEmpty()) return Report(Finding.EMPTY)
        if (cycles.size > 1000) return Report(Finding.TOO_MANY)
        for ((index, cycle) in cycles.withIndex()) {
            if (cycle.atMs <= 0L || cycle.v1.atMs != cycle.atMs ||
                cycle.v5.nowMs != cycle.atMs ||
                (index > 0 && cycle.atMs <= cycles[index - 1].atMs))
                return Report(Finding.INVALID_CLOCK, failedIndex = index)
            if (cycle.v1.cgm.bgMgDl != cycle.v5.bg ||
                cycle.v1.prediction.targetMgDl != cycle.v5.targetBg ||
                BoostV1CycleProvenanceAudit.inspect(cycle.v1).finding !=
                BoostV1CycleProvenanceAudit.Finding.READY)
                return Report(Finding.INVALID_V1, failedIndex = index)
        }
        val replay = BoostV5OfflineStatefulReplay.inspect(cycles.map { it.v5 }, initialState)
        if (replay.finding != BoostV5OfflineStatefulReplay.Finding.READY ||
            replay.observations.size != cycles.size)
            return Report(Finding.INVALID_V5, failedIndex = replay.firstInvalidIndex)
        return Report(Finding.READY, replay.observations.map {
            Observation(it.atMs, it.score, it.mealState)
        })
    }
}
