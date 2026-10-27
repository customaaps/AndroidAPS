package app.aaps.plugins.boostport.telemetry

/**
 * Disconnected in-process model of the archived V5StateStore cache-first
 * behavior. Unlike the original, it never reads or writes AAPS Preferences.
 *
 * A caller can seed it from a historical JSON fixture, then inspect or
 * replace a strictly validated snapshot. Rejected values do not erase an
 * existing valid session lock. Every operation is serialized on this instance.
 */
internal class AndroidV5OfflineStateCache {
    enum class Finding { EMPTY, INVALID_INPUT, SNAPSHOT_AVAILABLE }

    data class ReadResult(
        val finding: Finding,
        val snapshot: BoostV5StateSnapshotCodec.Snapshot? = null,
        val liveInvocationAllowed: Boolean = false,
        val insulinDeliveryAllowed: Boolean = false
    )

    private var cachedJson: String? = null

    @Synchronized
    fun seedFromLegacyJson(raw: String?): Finding {
        val snapshot = AndroidV5LegacyJsonSnapshotAdapter.inspect(raw)
            ?: return Finding.INVALID_INPUT
        val canonicalJson = AndroidV5LegacyJsonSnapshotAdapter.serialize(snapshot)
            ?: return Finding.INVALID_INPUT
        cachedJson = canonicalJson
        return Finding.SNAPSHOT_AVAILABLE
    }

    @Synchronized
    fun saveOffline(snapshot: BoostV5StateSnapshotCodec.Snapshot): Finding {
        val json = AndroidV5LegacyJsonSnapshotAdapter.serialize(snapshot)
            ?: return Finding.INVALID_INPUT
        cachedJson = json
        return Finding.SNAPSHOT_AVAILABLE
    }

    @Synchronized
    fun inspect(): ReadResult {
        val json = cachedJson ?: return ReadResult(Finding.EMPTY)
        val snapshot = AndroidV5LegacyJsonSnapshotAdapter.inspect(json)
            ?: return ReadResult(Finding.INVALID_INPUT)
        return ReadResult(Finding.SNAPSHOT_AVAILABLE, snapshot)
    }

    /** Explicitly discard an offline fixture, not an AAPS user preference. */
    @Synchronized
    fun clearOffline() {
        cachedJson = null
    }
}
