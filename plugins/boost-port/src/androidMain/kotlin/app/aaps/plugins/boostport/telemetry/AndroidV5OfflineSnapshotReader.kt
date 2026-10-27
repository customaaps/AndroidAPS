package app.aaps.plugins.boostport.telemetry

/**
 * Read-only boundary for importing a historical V5StateStore JSON value.
 *
 * The source callback must be supplied explicitly by an offline diagnostic
 * caller. There is no reference to AndroidAPS Preferences or a dosing plugin,
 * no write callback, no fallback to IDLE, and no pump integration.
 */
internal class AndroidV5OfflineSnapshotReader(
    private val readLegacyJson: () -> String?
) {
    enum class Finding { READ_FAILED, MISSING, INVALID, AVAILABLE }

    data class Result(
        val finding: Finding,
        val snapshot: BoostV5StateSnapshotCodec.Snapshot? = null,
        val canonicalJson: String? = null,
        val liveInvocationAllowed: Boolean = false,
        val insulinDeliveryAllowed: Boolean = false
    )

    fun inspect(): Result {
        val raw = try {
            readLegacyJson()
        } catch (_: Exception) {
            return Result(Finding.READ_FAILED)
        }
        if (raw.isNullOrBlank()) return Result(Finding.MISSING)
        val snapshot = AndroidV5LegacyJsonSnapshotAdapter.inspect(raw)
            ?: return Result(Finding.INVALID)
        val canonical = AndroidV5LegacyJsonSnapshotAdapter.serialize(snapshot)
            ?: return Result(Finding.INVALID)
        return Result(Finding.AVAILABLE, snapshot, canonical)
    }
}
