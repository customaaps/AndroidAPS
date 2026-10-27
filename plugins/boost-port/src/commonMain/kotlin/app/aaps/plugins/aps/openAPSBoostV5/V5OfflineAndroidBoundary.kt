package app.aaps.plugins.aps.openAPSBoostV5

/**
 * Disconnected boundary between the archived Android V5 state store and the
 * source V5 -> Boost V1 route. This object carries no Android, Preferences,
 * plugin-registration, scheduling or pump dependencies.
 *
 * A persisted session is never silently replaced with a fresh one.
 */
internal object V5OfflineAndroidBoundary {
    enum class Finding {
        MISSING_STATE,
        INVALID_STATE,
        MISSING_V1_PREREQUISITES,
        MISSING_V5_ENGINE,
        OFFLINE_READY
    }

    data class Report(
        val finding: Finding,
        val savedFields: Map<String, String>? = null,
        val v5ActiveInArchivedRoute: Boolean = true,
        val liveInvocationAllowed: Boolean = false,
        val insulinDeliveryAllowed: Boolean = false
    )

    fun inspect(
        persistedFields: Map<String, String>?,
        v1EngineInputsAvailable: Boolean,
        fallbackAvailable: Boolean,
        v5EngineAvailable: Boolean
    ): Report {
        val restored = V5OfflineStateHandoff.restore(persistedFields)
        if (restored.finding == V5OfflineStateHandoff.Finding.MISSING_SNAPSHOT) {
            return Report(Finding.MISSING_STATE)
        }
        val state = restored.state ?: return Report(Finding.INVALID_STATE)
        val route = V5V1DelegationOffline.inspect(
            V5V1DelegationOffline.Inputs(
                v1EngineInputsAvailable = v1EngineInputsAvailable,
                v5StateAvailable = true,
                v5EngineAvailable = v5EngineAvailable,
                fallbackAvailable = fallbackAvailable
            )
        )
        if (route.finding == V5V1DelegationOffline.Finding.V1_ENGINE_UNAVAILABLE) {
            return Report(Finding.MISSING_V1_PREREQUISITES)
        }
        if (route.finding != V5V1DelegationOffline.Finding.OFFLINE_ROUTE_DESCRIBED) {
            return Report(Finding.MISSING_V5_ENGINE)
        }
        val ready = V5OfflineAdapterGate.inspect(
            v1EngineInputsAvailable = v1EngineInputsAvailable,
            fallbackAvailable = fallbackAvailable,
            v5EngineAvailable = v5EngineAvailable,
            state = state
        )
        if (ready.finding != V5OfflineAdapterGate.Finding.OFFLINE_INPUTS_READY) {
            return Report(Finding.INVALID_STATE)
        }
        return Report(Finding.OFFLINE_READY, ready.snapshotFields?.toMap())
    }
}
