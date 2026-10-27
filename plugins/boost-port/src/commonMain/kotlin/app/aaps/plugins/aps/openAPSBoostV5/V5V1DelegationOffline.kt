package app.aaps.plugins.aps.openAPSBoostV5

/**
 * Historical routing contract extracted from OpenAPSBoostV5Plugin.invoke().
 *
 * The original selected V5 plugin delegates basal/prediction/sensitivity to
 * Boost V1 runEngine(..., v5Active=true). This offline contract records the
 * intended route but provides NO invocation callback or APS/pump connection.
 */
internal object V5V1DelegationOffline {
    enum class Finding {
        V1_ENGINE_UNAVAILABLE,
        V5_DECISION_UNAVAILABLE,
        OFFLINE_ROUTE_DESCRIBED,
    }

    data class Inputs(
        val v1EngineInputsAvailable: Boolean,
        val v5StateAvailable: Boolean,
        val v5EngineAvailable: Boolean,
        val fallbackAvailable: Boolean,
    )

    data class Report(
        val finding: Finding,
        val v1BaseEngineRequired: Boolean = true,
        val v5ActiveInOriginalPlugin: Boolean = true,
        val liveInvocationAllowed: Boolean = false,
        val insulinDeliveryAllowed: Boolean = false,
    )

    fun inspect(inputs: Inputs): Report {
        val finding = when {
            !inputs.v1EngineInputsAvailable || !inputs.fallbackAvailable ->
                Finding.V1_ENGINE_UNAVAILABLE
            !inputs.v5StateAvailable || !inputs.v5EngineAvailable ->
                Finding.V5_DECISION_UNAVAILABLE
            else -> Finding.OFFLINE_ROUTE_DESCRIBED
        }
        return Report(finding)
    }
}
