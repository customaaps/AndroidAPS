package app.aaps.plugins.boostport.telemetry

/**
 * Versioned, process-local storage for disconnected V5 replay fixtures.
 *
 * Optimistic revisions prevent two offline replay workers from silently
 * overwriting one another. This is not durable persistence, Preferences,
 * an APS state store, or a dosing safety mechanism.
 */
internal class AndroidV5OfflineRevisionStore {
    enum class Finding { EMPTY, INVALID_STATE, REVISION_CONFLICT, AVAILABLE }

    data class Inspection(
        val finding: Finding,
        val revision: Long,
        val snapshot: BoostV5StateSnapshotCodec.Snapshot? = null,
        val liveInvocationAllowed: Boolean = false,
        val insulinDeliveryAllowed: Boolean = false
    )

    private var revision = 0L
    private var serialized: String? = null

    @Synchronized
    fun inspect(): Inspection {
        val current = serialized ?: return Inspection(Finding.EMPTY, revision)
        val snapshot = AndroidV5LegacyJsonSnapshotAdapter.inspect(current)
            ?: return Inspection(Finding.INVALID_STATE, revision)
        return Inspection(Finding.AVAILABLE, revision, snapshot)
    }

    /** Requires the revision returned by inspect; failed writes change nothing. */
    @Synchronized
    fun replace(expectedRevision: Long, snapshot: BoostV5StateSnapshotCodec.Snapshot): Inspection {
        if (expectedRevision != revision || revision == Long.MAX_VALUE) {
            return Inspection(Finding.REVISION_CONFLICT, revision)
        }
        val next = AndroidV5LegacyJsonSnapshotAdapter.serialize(snapshot)
            ?: return Inspection(Finding.INVALID_STATE, revision)
        // Validate the serialized snapshot before updating the offline revision.
        val verified = AndroidV5LegacyJsonSnapshotAdapter.inspect(next)
            ?: return Inspection(Finding.INVALID_STATE, revision)
        serialized = next
        revision += 1L
        return Inspection(Finding.AVAILABLE, revision, verified)
    }

    /**
     * Read a detached, canonical JSON fixture. No persistence occurs.
     * A malformed internal snapshot is never returned to a replay consumer.
     */
    @Synchronized
    fun exportFixture(): String? {
        val current = serialized ?: return null
        val validated = AndroidV5LegacyJsonSnapshotAdapter.inspect(current) ?: return null
        return AndroidV5LegacyJsonSnapshotAdapter.serialize(validated)
    }

    /**
     * Clearing a fixture also requires a fresh revision; stale readers cannot
     * erase the currently inspected session by mistake.
     */
    @Synchronized
    fun clearFixture(expectedRevision: Long): Inspection {
        if (expectedRevision != revision || revision == Long.MAX_VALUE) {
            return Inspection(Finding.REVISION_CONFLICT, revision)
        }
        serialized = null
        revision += 1L
        return Inspection(Finding.EMPTY, revision)
    }

    /** Fixture seeding uses the same revision guard and validation as updates. */
    @Synchronized
    fun importFixture(expectedRevision: Long, json: String?): Inspection {
        if (expectedRevision != revision || revision == Long.MAX_VALUE) {
            return Inspection(Finding.REVISION_CONFLICT, revision)
        }
        val snapshot = AndroidV5LegacyJsonSnapshotAdapter.inspect(json)
            ?: return Inspection(Finding.INVALID_STATE, revision)
        return replace(expectedRevision, snapshot)
    }
}
