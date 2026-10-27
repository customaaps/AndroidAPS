package app.aaps.plugins.boostport.telemetry

import app.aaps.plugins.boostport.telemetry.BoostV5ConfigResolutionAudit.Knob as K
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoostV5SchemaMigrationAuditTest {
    private val audit = BoostV5SchemaMigrationAudit

    @Test fun oldEraFactoryCapsAreReopenedInSchemaV2() {
        val flags = setOf(K.CONFIRMED_CAP, K.COMMITTED_CAP, K.CUMULATIVE_SMB_CAP)
        val preview = audit.inspectSchema(0, flags, mapOf(
            K.CONFIRMED_CAP to 1.0, K.COMMITTED_CAP to 0.25,
            K.CUMULATIVE_SMB_CAP to 6.0,
        ))!!
        assertEquals(flags, preview.wouldReopenResolvedKeys)
        assertTrue(preview.wouldKeepResolvedKeys.isEmpty())
        assertEquals(2, preview.wouldStampVersion)
    }

    @Test fun genuinelyCustomValueRemainsProtected() {
        val flags = setOf(K.CONFIRMED_CAP, K.COMMITTED_CAP)
        val preview = audit.inspectSchema(0, flags, mapOf(
            K.CONFIRMED_CAP to 1.0, K.COMMITTED_CAP to 1.37,
        ))!!
        assertEquals(setOf(K.CONFIRMED_CAP), preview.wouldReopenResolvedKeys)
        assertEquals(setOf(K.COMMITTED_CAP), preview.wouldKeepResolvedKeys)
    }

    @Test fun missingStoredValueIsNotProofOfUserTuning() {
        val preview = audit.inspectSchema(0, setOf(K.HYPO_CAUTION),
            emptyMap())!!
        assertEquals(setOf(K.HYPO_CAUTION), preview.wouldReopenResolvedKeys)
    }

    @Test fun currentFactoryValuesAlsoReopenOnMigration() {
        val preview = audit.inspectSchema(1, setOf(K.AGGRESSION, K.CONFIRMED_CAP),
            mapOf(K.AGGRESSION to 1.0, K.CONFIRMED_CAP to 2.5))!!
        assertEquals(setOf(K.AGGRESSION, K.CONFIRMED_CAP),
            preview.wouldReopenResolvedKeys)
    }

    @Test fun currentSchemaIsIdempotentAndNeverStampsVersionAgain() {
        val preview = audit.inspectSchema(2, setOf(K.CONFIRMED_CAP),
            mapOf(K.CONFIRMED_CAP to 2.5))!!
        assertNull(preview.wouldStampVersion)
        assertTrue(preview.wouldReopenResolvedKeys.isEmpty())
        assertEquals(setOf(K.CONFIRMED_CAP), preview.wouldKeepResolvedKeys)
    }

    @Test fun futureSchemaIsNeverDowngraded() {
        val preview = audit.inspectSchema(3, setOf(K.CONFIRMED_CAP),
            mapOf(K.CONFIRMED_CAP to 1.0))!!
        assertNull(preview.wouldStampVersion)
        assertTrue(preview.wouldReopenResolvedKeys.isEmpty())
    }

    @Test fun previousGlobalDoneFlagMarksOnlyActuallyTunedKeys() {
        val preview = audit.inspectLegacyDoneFlag(true, mapOf(
            K.CONFIRMED_CAP to 1.0,
            K.COMMITTED_CAP to 0.25,
            K.AGGRESSION to 0.92,
            K.CUMULATIVE_SMB_CAP to 1.5,
            K.HYPO_CAUTION to 1.4,
        ))!!
        assertEquals(setOf(K.AGGRESSION, K.HYPO_CAUTION),
            preview.wouldMarkResolvedKeys)
        assertTrue(K.CONFIRMED_CAP in preview.wouldLeaveUnresolvedKeys)
        assertTrue(K.COMMITTED_CAP in preview.wouldLeaveUnresolvedKeys)
    }

    @Test fun absentGlobalDoneFlagMustNotResolveAnyKnob() {
        val preview = audit.inspectLegacyDoneFlag(false,
            mapOf(K.AGGRESSION to 0.85))!!
        assertTrue(preview.wouldMarkResolvedKeys.isEmpty())
        assertEquals(K.values().toSet(), preview.wouldLeaveUnresolvedKeys)
    }

    @Test fun independentKnobsDoNotBlockOtherUnresolvedKnobs() {
        val preview = audit.inspectSchema(0,
            setOf(K.HYPO_CAUTION, K.AGGRESSION),
            mapOf(K.HYPO_CAUTION to 1.5, K.AGGRESSION to 1.0))!!
        assertEquals(setOf(K.AGGRESSION), preview.wouldReopenResolvedKeys)
        assertEquals(setOf(K.HYPO_CAUTION), preview.wouldKeepResolvedKeys)
    }

    @Test fun finiteNegativeAndNanPreferencesMustNeverBeReinterpretedAsFactory() {
        assertNull(audit.inspectSchema(0, setOf(K.AGGRESSION),
            mapOf(K.AGGRESSION to Double.NaN)))
        assertNull(audit.inspectSchema(0, emptySet(),
            mapOf(K.COMMITTED_CAP to -1.0)))
        assertNull(audit.inspectLegacyDoneFlag(true,
            mapOf(K.CONFIRMED_CAP to Double.POSITIVE_INFINITY)))
        assertNull(audit.inspectSchema(-1, emptySet(), emptyMap()))
    }

    @Test fun migrationPreviewDoesNotMutateStoredOrResolvedInputs() {
        val stored = mapOf(K.COMMITTED_CAP to 0.25, K.AGGRESSION to 0.9)
        val flags = setOf(K.COMMITTED_CAP, K.AGGRESSION)
        val preview = audit.inspectSchema(0, flags, stored)!!
        assertFalse(K.AGGRESSION in preview.wouldReopenResolvedKeys)
        assertEquals(0.25, stored[K.COMMITTED_CAP])
        assertEquals(flags, setOf(K.COMMITTED_CAP, K.AGGRESSION))
    }

    @Test fun legacyDoneWithoutStoredOverridesLeavesAllKnobsUnresolved() {
        val preview = audit.inspectLegacyDoneFlag(true, emptyMap())!!
        assertTrue(preview.wouldMarkResolvedKeys.isEmpty())
        assertEquals(K.values().toSet(), preview.wouldLeaveUnresolvedKeys)
    }

    @Test fun corruptLegacyDataMustFailClosedEvenWhenDoneFlagIsFalse() {
        assertNull(audit.inspectLegacyDoneFlag(false,
            mapOf(K.AGGRESSION to Double.NaN)))
        assertNull(audit.inspectLegacyDoneFlag(false,
            mapOf(K.CONFIRMED_CAP to -0.1)))
    }

    @Test fun migrationPreviewIsRepeatableAndNeverChangesInputMaps() {
        val originals = linkedMapOf(K.CONFIRMED_CAP to 1.0, K.AGGRESSION to 0.91)
        val flags = linkedSetOf(K.CONFIRMED_CAP, K.AGGRESSION)
        val first = audit.inspectSchema(1, flags, originals)!!
        val second = audit.inspectSchema(1, flags, originals)!!
        assertEquals(first, second)
        assertEquals(mapOf(K.CONFIRMED_CAP to 1.0, K.AGGRESSION to 0.91), originals)
        assertEquals(setOf(K.CONFIRMED_CAP, K.AGGRESSION), flags)
        assertEquals(setOf(K.CONFIRMED_CAP), first.wouldReopenResolvedKeys)
        assertEquals(setOf(K.AGGRESSION), first.wouldKeepResolvedKeys)
    }
}
