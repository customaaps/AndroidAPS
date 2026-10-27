package app.aaps.plugins.boostport

import app.aaps.plugins.boostport.telemetry.BoostV5StateSnapshotCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Independent field-name contract transcribed from archived V5StateStore.save().
 * This does not deserialize JSON or use device Preferences.
 */
class BoostV5SourceSchemaParityTest {
    private val sourceFields = setOf(
        "mealHypothesis", "mealHypothesisAge", "mealHypothesisLastAgeMs",
        "maxScoreInObserving", "maxEventualBgOffsetInObserving", "committedInSession",
        "lastCommitMs", "nonPositiveRunStartMs", "maxScoreAtMs", "maxOffsetAtMs",
        "mlMealLikelyNullStreak", "primerAppliedU", "primerNettingResidualU",
        "primerIobU", "primerIobUpdatedMs",
    )

    @Test fun encodedKeySetMatchesArchivedV5StateStoreExactly() {
        val encoded = assertNotNull(BoostV5StateSnapshotCodec.encode(BoostV5StateSnapshotCodec.Snapshot()))
        assertEquals(sourceFields, encoded.keys)
        assertFalse("lastCycleScore" in encoded)
    }

    @Test fun oldRequiredFieldsCannotBeSilentlyDefaulted() {
        val valid = assertNotNull(BoostV5StateSnapshotCodec.encode(BoostV5StateSnapshotCodec.Snapshot()))
        assertNull(BoostV5StateSnapshotCodec.decode(valid - "mealHypothesis"))
        assertNull(BoostV5StateSnapshotCodec.decode(valid - "mealHypothesisAge"))
    }

    @Test fun legacyOptionalFieldsCanBeAbsentWithoutInventingLock() {
        val valid = assertNotNull(BoostV5StateSnapshotCodec.encode(BoostV5StateSnapshotCodec.Snapshot()))
        val minimal = valid.filterKeys { it == "mealHypothesis" || it == "mealHypothesisAge" }
        assertEquals(BoostV5StateSnapshotCodec.Snapshot(), BoostV5StateSnapshotCodec.decode(minimal))
    }

    @Test fun invalidSessionLockAndNonfinitePrimerFailClosed() {
        val valid = assertNotNull(BoostV5StateSnapshotCodec.encode(BoostV5StateSnapshotCodec.Snapshot()))
        assertNull(BoostV5StateSnapshotCodec.decode(valid + ("committedInSession" to "maybe")))
        assertNull(BoostV5StateSnapshotCodec.decode(valid + ("primerIobU" to "NaN")))
    }
}
