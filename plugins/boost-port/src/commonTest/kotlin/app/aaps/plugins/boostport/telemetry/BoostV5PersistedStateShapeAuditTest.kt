package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoostV5PersistedStateShapeAuditTest {
    private val audit = BoostV5PersistedStateShapeAudit
    private val minimal = mapOf("mealHypothesis" to "IDLE", "mealHypothesisAge" to "0")

    @Test fun missingRequiredStateCannotBeRestored() {
        val result = audit.inspect(mapOf("mealHypothesis" to "IDLE"))
        assertEquals(BoostV5PersistedStateShapeAudit.Finding.MISSING_REQUIRED, result.finding)
    }

    @Test fun legacyStateDistinguishedFromFullyPopulatedSchema() {
        val result = audit.inspect(minimal)
        assertEquals(BoostV5PersistedStateShapeAudit.Finding.LEGACY_DEFAULTS, result.finding)
        assertTrue(result.missingLegacyFields.isNotEmpty())
    }

    @Test fun malformedPersistedStateIsNotAccepted() {
        assertEquals(BoostV5PersistedStateShapeAudit.Finding.MALFORMED,
            audit.inspect(minimal + ("mealHypothesisAge" to "-1")).finding)
    }

    @Test fun unknownFutureFieldIsNotSilentlyDiscarded() {
        val result = audit.inspect(minimal + ("futureVersionField" to "1"))
        assertEquals(BoostV5PersistedStateShapeAudit.Finding.FUTURE_FIELDS, result.finding)
        assertEquals(setOf("futureVersionField"), result.unknownFields)
    }

    @Test fun cacheOnlyLastCycleScoreCannotBeMistakenForPersistedField() {
        assertEquals(BoostV5PersistedStateShapeAudit.Finding.CACHE_ONLY_FIELD,
            audit.inspect(minimal + ("lastCycleScore" to "0.5")).finding)
    }

    @Test fun validCanonicalStateContainsNoUnknownFields() {
        val encoded = BoostV5StateSnapshotCodec.encode(
            BoostV5StateSnapshotCodec.Snapshot()
        )!!
        val result = audit.inspect(encoded)
        assertEquals(BoostV5PersistedStateShapeAudit.Finding.CANONICAL, result.finding)
        assertTrue(result.missingLegacyFields.isEmpty())
    }
}
