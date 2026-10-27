package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoostV5StateSnapshotCodecTest {
    private val codec = BoostV5StateSnapshotCodec

    private fun snapshot() = BoostV5StateSnapshotCodec.Snapshot(
        meal = BoostV5MealStateDiagnostics.State(
            phase = BoostV5MealStateDiagnostics.Phase.RECOVERING,
            ageCycles = 3,
            peakScore = 0.7,
            peakOffset = 40.0,
            committedInSession = true,
            lastAgeMs = 1_700_000_000_000L,
            lastCommitMs = 1_699_999_900_000L,
            nonPositiveStartMs = 1_699_999_950_000L,
            peakScoreAtMs = 1_699_999_980_000L,
            peakOffsetAtMs = 1_699_999_970_000L,
        ),
        mlMealLikelyNullStreak = 4,
        primerAppliedU = 0.25,
        primerNettingResidualU = -0.05,
        primerIobU = 0.2,
        primerIobUpdatedMs = 1_700_000_000_000L,
    )

    @Test fun everyLegacyFieldRoundTripsWithoutStateLoss() {
        val value = snapshot()
        val encoded = codec.encode(value)
        assertNotNull(encoded)
        assertEquals(value, codec.decode(encoded))
        assertEquals(15, encoded.size)
    }

    @Test fun sessionLockAndCommitClockSurviveSnapshotRoundTrip() {
        val recovered = codec.decode(codec.encode(snapshot())!!)!!
        assertTrue(recovered.meal.committedInSession)
        assertEquals(snapshot().meal.lastCommitMs, recovered.meal.lastCommitMs)
        assertEquals(snapshot().meal.nonPositiveStartMs, recovered.meal.nonPositiveStartMs)
    }

    @Test fun cacheOnlyLastCycleScoreIsIntentionallyNotSerialized() {
        val encoded = codec.encode(snapshot())!!
        assertFalse("lastCycleScore" in encoded)
        assertFalse("scoreReadyStreak" in encoded)
    }

    @Test fun requiredPhaseAndAgeCannotBeAbsent() {
        val fields = codec.encode(snapshot())!!
        assertNull(codec.decode(fields - "mealHypothesis"))
        assertNull(codec.decode(fields - "mealHypothesisAge"))
    }

    @Test fun malformedPhaseCannotFallBackToActiveState() {
        val fields = codec.encode(snapshot())!!
        assertNull(codec.decode(fields + ("mealHypothesis" to "INJECTING")))
        assertNull(codec.decode(fields + ("mealHypothesis" to "")))
    }

    @Test fun olderSchemaMissingPeakClocksDefaultsToZero() {
        val source = codec.encode(snapshot())!!
        val legacy = source - setOf(
            "lastCommitMs", "nonPositiveRunStartMs",
            "maxScoreAtMs", "maxOffsetAtMs", "mealHypothesisLastAgeMs",
        )
        val decoded = codec.decode(legacy)!!
        assertEquals(0L, decoded.meal.lastCommitMs)
        assertEquals(0L, decoded.meal.nonPositiveStartMs)
        assertEquals(0L, decoded.meal.peakScoreAtMs)
        assertTrue(decoded.meal.committedInSession)
    }

    @Test fun olderSchemaMissingPrimerFieldsDefaultsToNoPrimer() {
        val source = codec.encode(snapshot())!!
        val legacy = source - setOf(
            "primerAppliedU", "primerNettingResidualU",
            "primerIobU", "primerIobUpdatedMs",
        )
        val decoded = codec.decode(legacy)!!
        assertEquals(0.0, decoded.primerAppliedU)
        assertEquals(0.0, decoded.primerIobU)
        assertEquals(0L, decoded.primerIobUpdatedMs)
    }

    @Test fun invalidNumericFieldDoesNotSilentlyResetLockOrEvidence() {
        val fields = codec.encode(snapshot())!!
        assertNull(codec.decode(fields + ("lastCommitMs" to "not-an-integer")))
        assertNull(codec.decode(fields + ("maxScoreAtMs" to "-5")))
        assertNull(codec.decode(fields + ("maxScoreInObserving" to "NaN")))
        assertNull(codec.decode(fields + ("mlMealLikelyNullStreak" to "-1")))
    }

    @Test fun falseLockFieldMustBeValidStrictBoolean() {
        val fields = codec.encode(snapshot())!!
        assertNull(codec.decode(fields + ("committedInSession" to "perhaps")))
        assertEquals(false, codec.decode(
            fields + ("committedInSession" to "false"))!!.meal.committedInSession)
    }

    @Test fun invalidPrimerSnapshotCannotBeExportedOrImported() {
        val base = snapshot()
        assertNull(codec.encode(base.copy(primerIobU = Double.POSITIVE_INFINITY)))
        assertNull(codec.decode(codec.encode(base)!! + ("primerAppliedU" to "-1.0")))
    }

    @Test fun unknownFutureSchemaFieldsAreIgnoredDuringReadOnlyAudit() {
        val fields = codec.encode(snapshot())!!
        assertEquals(snapshot(), codec.decode(fields + ("futureOptionalKey" to "value")))
    }
}
