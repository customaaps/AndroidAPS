package app.aaps.plugins.aps.openAPSBoostV5

/**
 * Pure Android-adapter preflight. Deliberately has no callback, dependency on
 * Android Preferences, APS registration or insulin-delivery capability.
 * The eventual platform adapter must separately verify device safety.
 */
internal object V5OfflineAdapterGate {
    enum class Finding {
        MISSING_V1_INPUT,
        MISSING_V5_STATE,
        INVALID_STATE_SNAPSHOT,
        OFFLINE_INPUTS_READY
    }

    data class Inspection(
        val finding: Finding,
        val snapshotFields: Map<String, String>? = null,
        val liveInvocationAllowed: Boolean = false,
        val insulinDeliveryAllowed: Boolean = false
    )

    fun inspect(
        v1EngineInputsAvailable: Boolean,
        fallbackAvailable: Boolean,
        v5EngineAvailable: Boolean,
        state: V5PersistedState?
    ): Inspection {
        if (!v1EngineInputsAvailable || !fallbackAvailable) {
            return Inspection(Finding.MISSING_V1_INPUT)
        }
        if (!v5EngineAvailable || state == null) {
            return Inspection(Finding.MISSING_V5_STATE)
        }
        val fields = V5StateStoreOfflineBridge.toFields(state)
            ?: return Inspection(Finding.INVALID_STATE_SNAPSHOT)
        // Require a reversible snapshot before an offline replay is described.
        if (V5StateStoreOfflineBridge.fromFields(fields) == null) {
            return Inspection(Finding.INVALID_STATE_SNAPSHOT)
        }
        return Inspection(Finding.OFFLINE_INPUTS_READY, fields.toMap())
    }
}
