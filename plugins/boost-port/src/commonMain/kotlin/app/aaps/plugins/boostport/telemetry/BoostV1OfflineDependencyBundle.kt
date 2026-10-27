package app.aaps.plugins.boostport.telemetry

import app.aaps.plugins.aps.openAPSBoost.BoostV1OfflineCycleMath

/** Passive consistency check for caller-supplied V1 offline snapshots. */
internal object BoostV1OfflineDependencyBundle {
    enum class Finding { INVALID_ENGINE_INPUT, MISMATCHED_SNAPSHOTS, INVALID_SCALARS, READY }
    data class Input(
        val engine: BoostV1EngineInputContract.Inputs,
        val scalar: BoostV1OfflineCycleMath.Input,
        val comparisonTimeMs: Long
    )
    data class Report(val finding: Finding, val canDeliverInsulin: Boolean = false)

    fun inspect(input: Input): Report {
        if (BoostV1EngineInputContract.inspect(input.engine).finding !=
            BoostV1EngineInputContract.Finding.READY_OFFLINE)
            return Report(Finding.INVALID_ENGINE_INPUT)
        if (input.comparisonTimeMs != input.engine.nowMs ||
            input.engine.glucose.mgDl != input.scalar.bgMgDl ||
            input.engine.profile.targetMgDl != input.scalar.targetBg)
            return Report(Finding.MISMATCHED_SNAPSHOTS)
        if (BoostV1OfflineCycleMath.inspect(input.scalar).finding !=
            BoostV1OfflineCycleMath.Finding.READY)
            return Report(Finding.INVALID_SCALARS)
        return Report(Finding.READY)
    }
}
