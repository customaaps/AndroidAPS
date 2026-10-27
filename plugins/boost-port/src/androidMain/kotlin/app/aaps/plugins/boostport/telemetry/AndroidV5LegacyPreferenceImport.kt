package app.aaps.plugins.boostport.telemetry

/**
 * Read-only compatibility boundary for importing archived V5 preference data.
 *
 * The legacy StringKey.ApsBoostV5State and getBoostDosing extension are absent
 * from this target branch. The actual AAPS Preferences must NOT be queried
 * through a substitute or through simple-mode-masked get(StringKey).
 *
 * A diagnostic caller must explicitly attest that it obtained the raw legacy
 * blob from a reviewed, independently supplied source. No write capability.
 */
internal object AndroidV5LegacyPreferenceImport {
    enum class Finding { UNVERIFIED_SOURCE, SOURCE_READ_FAILED, MISSING, INVALID, AVAILABLE }

    data class Result(
        val finding: Finding,
        val snapshot: BoostV5StateSnapshotCodec.Snapshot? = null,
        val liveInvocationAllowed: Boolean = false,
        val insulinDeliveryAllowed: Boolean = false
    )

    fun inspect(verifiedRawSource: Boolean, readRawLegacyValue: () -> String?): Result {
        if (!verifiedRawSource) return Result(Finding.UNVERIFIED_SOURCE)
        val imported = AndroidV5OfflineSnapshotReader(readRawLegacyValue).inspect()
        return Result(
            when (imported.finding) {
                AndroidV5OfflineSnapshotReader.Finding.READ_FAILED -> Finding.SOURCE_READ_FAILED
                AndroidV5OfflineSnapshotReader.Finding.MISSING -> Finding.MISSING
                AndroidV5OfflineSnapshotReader.Finding.INVALID -> Finding.INVALID
                AndroidV5OfflineSnapshotReader.Finding.AVAILABLE -> Finding.AVAILABLE
            },
            imported.snapshot
        )
    }
}
