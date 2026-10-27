package app.aaps.plugins.boostport.telemetry

import app.aaps.core.keys.StringNonKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Regression contract for the reserved legacy V5 state key.
 * Does not access a live Preferences implementation or enable dosing.
 */
class BoostV5LegacyPreferenceKeyTest {
    @Test fun stateKeyMatchesArchivedStorageSlot() {
        assertEquals("boost_v5_state", StringNonKey.BoostV5OfflineLegacyState.key)
        assertEquals("", StringNonKey.BoostV5OfflineLegacyState.defaultValue)
    }

    @Test fun stateKeyCannotExportOrSynchronize() {
        assertFalse(StringNonKey.BoostV5OfflineLegacyState.exportable)
        assertNull(StringNonKey.BoostV5OfflineLegacyState.sync)
    }
}
