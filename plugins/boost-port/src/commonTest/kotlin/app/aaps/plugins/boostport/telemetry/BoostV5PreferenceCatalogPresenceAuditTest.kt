package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals

class BoostV5PreferenceCatalogPresenceAuditTest {
    private val audit = BoostV5PreferenceCatalogPresenceAudit
    private val all = BoostV5ConfigResolutionAudit.Knob.entries.mapNotNull {
        BoostV5PreferenceKeyMapAudit.symbolicName(it)
    }

    @Test fun currentTargetWithoutBoostKeysIsReportedAsMissing() {
        val result = audit.inspect(listOf("ApsSmbMaxIob", "ApsAmaMaxIob"))
        assertEquals(BoostV5PreferenceCatalogPresenceAudit.Finding.ALL_MISSING, result.finding)
        assertEquals(8, result.missing.size)
    }

    @Test fun fullSymbolicCatalogueIsPresent() {
        val result = audit.inspect(all)
        assertEquals(BoostV5PreferenceCatalogPresenceAudit.Finding.ALL_PRESENT, result.finding)
        assertEquals(0, result.missing.size)
    }

    @Test fun partialCatalogueIsDistinguished() {
        val result = audit.inspect(listOf(all.first()))
        assertEquals(BoostV5PreferenceCatalogPresenceAudit.Finding.PARTIALLY_MISSING, result.finding)
        assertEquals(7, result.missing.size)
    }

    @Test fun duplicatesAreNotAcceptedAsComplete() {
        val result = audit.inspect(all + all.first())
        assertEquals(BoostV5PreferenceCatalogPresenceAudit.Finding.DUPLICATE_DECLARATIONS, result.finding)
    }
}
