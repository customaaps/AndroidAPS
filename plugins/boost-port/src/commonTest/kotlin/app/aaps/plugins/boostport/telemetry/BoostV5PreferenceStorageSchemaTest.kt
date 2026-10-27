package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoostV5PreferenceStorageSchemaTest {
    private val schema = BoostV5PreferenceStorageSchema

    @Test fun archivedEightKeysAreUniqueAndWithinHistoricalBounds() {
        assertTrue(schema.isConsistent())
        assertEquals(8, schema.all().size)
        assertTrue(schema.all().all { it.hiddenBySimpleMode })
    }

    @Test fun symbolicAndStoredIdentifiersAreDifferentNamespaces() {
        val aggression = schema.bySymbolicName("ApsBoostV5Aggression")
        assertNotNull(aggression)
        assertEquals("boost_v5_aggression", aggression.storageKey)
        assertEquals(1.0, aggression.factoryDefault)
        assertEquals(0.7, aggression.minimum)
        assertEquals(1.6, aggression.maximum)
        assertNull(schema.byStorageKey("ApsBoostV5Aggression"))
    }

    @Test fun archivedPrimerDefaultIsZeroWithoutActivatingIt() {
        val primer = schema.byStorageKey("boost_v5_primer_cap_u")
        assertNotNull(primer)
        assertEquals(0.0, primer.factoryDefault)
        assertEquals(2.5, primer.maximum)
    }

    @Test fun symbolicNamesAgreeWithExistingPassiveCatalogue() {
        val names = BoostV5ConfigResolutionAudit.Knob.entries.mapNotNull {
            BoostV5PreferenceKeyMapAudit.symbolicName(it)
        }.toSet()
        assertEquals(names, schema.all().map { it.symbolicName }.toSet())
    }
}
