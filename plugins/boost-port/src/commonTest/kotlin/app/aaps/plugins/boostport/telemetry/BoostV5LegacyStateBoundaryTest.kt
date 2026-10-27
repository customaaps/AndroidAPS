package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Portable regressions for the isolated Android legacy JSON adapter's field codec. */
class BoostV5LegacyStateBoundaryTest {
    @Test fun missingMandatoryFieldsAreRejected() {
        assertNull(BoostV5StateSnapshotCodec.decode(emptyMap()))
        assertNull(BoostV5StateSnapshotCodec.decode(mapOf("mealHypothesis" to "IDLE")))
    }

    @Test fun corruptedSessionLockNeverBecomesUnlockedState() {
        val baseline = requireNotNull(BoostV5StateSnapshotCodec.encode(BoostV5StateSnapshotCodec.Snapshot()))
        assertNull(BoostV5StateSnapshotCodec.decode(baseline + ("committedInSession" to "broken")))
    }

    @Test fun nonFinitePrimerValuesAreRejected() {
        val baseline = requireNotNull(BoostV5StateSnapshotCodec.encode(BoostV5StateSnapshotCodec.Snapshot()))
        assertNull(BoostV5StateSnapshotCodec.decode(baseline + ("primerAppliedU" to "NaN")))
        assertNull(BoostV5StateSnapshotCodec.decode(baseline + ("primerIobU" to "Infinity")))
    }

    @Test fun canonicalOfflineSnapshotRoundTripsWithLockField() {
        val baseline = requireNotNull(BoostV5StateSnapshotCodec.encode(BoostV5StateSnapshotCodec.Snapshot()))
        val locked = baseline + ("committedInSession" to "true")
        val decoded = requireNotNull(BoostV5StateSnapshotCodec.decode(locked))
        assertEquals(true, decoded.meal.committedInSession)
        assertEquals(locked, BoostV5StateSnapshotCodec.encode(decoded))
    }
    @Test fun historicalMissingOptionalFieldsGetSourceDefaults() {
        val older = mapOf("mealHypothesis" to "OBSERVING", "mealHypothesisAge" to "2")
        val restored = requireNotNull(BoostV5StateSnapshotCodec.decode(older))
        assertEquals(0L, restored.meal.lastCommitMs)
        assertEquals(false, restored.meal.committedInSession)
        assertEquals(0.0, restored.primerAppliedU)
    }

    @Test fun negativeAndNonFinitePersistedValuesAreRejected() {
        val fields = requireNotNull(BoostV5StateSnapshotCodec.encode(BoostV5StateSnapshotCodec.Snapshot()))
        assertNull(BoostV5StateSnapshotCodec.decode(fields + ("mealHypothesisAge" to "-1")))
        assertNull(BoostV5StateSnapshotCodec.decode(fields + ("lastCommitMs" to "-10")))
        assertNull(BoostV5StateSnapshotCodec.decode(fields + ("maxScoreInObserving" to "NaN")))
        assertNull(BoostV5StateSnapshotCodec.decode(fields + ("primerIobUpdatedMs" to "-1")))
    }

    @Test fun exactLockAndClockFieldsSurviveRoundTrip() {
        val fields = requireNotNull(BoostV5StateSnapshotCodec.encode(BoostV5StateSnapshotCodec.Snapshot()))
        val restored = requireNotNull(BoostV5StateSnapshotCodec.decode(
            fields + mapOf("committedInSession" to "true", "lastCommitMs" to "123456", "maxScoreAtMs" to "123000")
        ))
        val reencoded = requireNotNull(BoostV5StateSnapshotCodec.encode(restored))
        assertEquals("true", reencoded["committedInSession"])
        assertEquals("123456", reencoded["lastCommitMs"])
        assertEquals("123000", reencoded["maxScoreAtMs"])
        assertTrue(reencoded.keys.containsAll(fields.keys))
    }

}
