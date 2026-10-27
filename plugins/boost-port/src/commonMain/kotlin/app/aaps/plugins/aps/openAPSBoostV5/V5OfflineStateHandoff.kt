package app.aaps.plugins.aps.openAPSBoostV5

/**
 * Immutable handoff for an offline Android V5 adapter. No I/O, registration,
 * scheduling, pump access or callable dosing implementation.
 *
 * A restore failure never substitutes a new unlocked meal session for a
 * previously persisted session: the caller must explicitly reject the replay.
 */
internal object V5OfflineStateHandoff {
    enum class Finding { MISSING_SNAPSHOT, INVALID_SNAPSHOT, OFFLINE_RESTORED }

    data class Result(
        val finding: Finding,
        val state: V5PersistedState? = null,
        val liveInvocationAllowed: Boolean = false,
        val insulinDeliveryAllowed: Boolean = false
    )

    fun restore(fields: Map<String, String>?): Result {
        if (fields == null) return Result(Finding.MISSING_SNAPSHOT)
        val restored = V5StateStoreOfflineBridge.fromFields(fields)
            ?: return Result(Finding.INVALID_SNAPSHOT)
        return Result(Finding.OFFLINE_RESTORED, restored)
    }

    fun export(state: V5PersistedState?): Map<String, String>? =
        state?.let { V5StateStoreOfflineBridge.toFields(it)?.toMap() }
}
