package app.aaps.plugins.boostport.telemetry

import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences

/**
 * Offline diagnostic bridge to the historical "boost_v5_state" value.
 *
 * Using a StringNonKey raw read avoids Simple Mode presentation masking.
 * This is a read-only importer: no put(), no replacement of invalid state,
 * no dosing plugin registration and no pump callbacks.
 */
internal class AndroidV5LegacyPreferencesReadOnly(
    private val preferences: Preferences
) {
    fun inspect(): AndroidV5OfflineSnapshotReader.Result =
        AndroidV5OfflineSnapshotReader {
            // getIfExists returns null for a missing key; never seed IDLE here.
            preferences.getIfExists(StringNonKey.BoostV5OfflineLegacyState)
        }.inspect()
}
