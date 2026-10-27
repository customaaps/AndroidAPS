package app.aaps.plugins.boostport.telemetry

import app.aaps.plugins.aps.openAPSBoost.BoostV1OfflineCycleMath
import app.aaps.plugins.aps.openAPSBoostV5.V5Inputs
import app.aaps.plugins.aps.openAPSBoostV5.V5PersistedState

/**
 * Explicit offline-only same-clock diagnostic orchestration.
 * V1 here is a set of scalar functions, NOT full DetermineBasalBoost.
 * V5 is invoked via a shadow runner which intentionally returns no dose.
 */
internal object BoostV1V5OfflinePairedReplay {
    enum class Finding { INVALID_CLOCK, INVALID_V1, INVALID_V5, READY }
    data class Input(
        val atMs: Long,
        val v1: BoostV1OfflineCycleMath.Input,
        val v5: V5Inputs,
        val priorV5: V5PersistedState,
    )
    data class Report(
        val finding: Finding,
        val v1: BoostV1OfflineCycleMath.Report? = null,
        val v5: BoostV5PureEngineShadowRunner.Report? = null,
        val canDeliverInsulin: Boolean = false,
    )

    fun inspect(input: Input): Report {
        if (input.atMs <= 0L || input.v5.nowMs != input.atMs)
            return Report(Finding.INVALID_CLOCK)
        val v1 = BoostV1OfflineCycleMath.inspect(input.v1)
        if (v1.finding != BoostV1OfflineCycleMath.Finding.READY)
            return Report(Finding.INVALID_V1, v1 = v1)
        val v5 = BoostV5PureEngineShadowRunner.inspect(input.v5, input.priorV5)
        if (v5.finding != BoostV5PureEngineShadowRunner.Finding.CALCULATED_OFFLINE)
            return Report(Finding.INVALID_V5, v1 = v1, v5 = v5)
        return Report(Finding.READY, v1 = v1, v5 = v5)
    }
}
