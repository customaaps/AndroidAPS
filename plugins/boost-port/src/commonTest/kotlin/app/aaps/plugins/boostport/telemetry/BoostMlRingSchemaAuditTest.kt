package app.aaps.plugins.boostport.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoostMlRingSchemaAuditTest {
    private val codec = BoostMlRingSchemaAudit
    private val start = 1_700_000_000_000L
    private val minute = 60_000L

    private fun snapshot(minutes: Long, bg: Double = 110.0) =
        BoostMlFeatureDiagnostics.Snapshot(
            timestampMs = start + minutes * minute,
            cgmMgdl = bg,
            iobIob = 1.3,
            iobActivity = 0.12,
            eventualBg = 126.0,
            recentSmbUnits60m = 0.4,
            minDelta = -0.3,
        )

    private fun ring(vararg minutes: Long): BoostMlFeatureDiagnostics.Ring {
        var r = BoostMlFeatureDiagnostics.Ring()
        minutes.forEach { r = r.accept(snapshot(it)) }
        return r
    }

    @Test fun emptyRingSchemaRoundTripsWithoutInventingHistory() {
        val records = codec.encode(ring())!!
        assertTrue(records.isEmpty())
        assertEquals(0, codec.decode(records)!!.size)
    }

    @Test fun allSevenOriginalFieldsArePreservedExactly() {
        val rows = codec.encode(ring(0))!!
        assertEquals(codec.FIELDS.toSet(), rows.single().keys)
        assertEquals(start.toString(), rows.single()["ts"])
        assertEquals("110.0", rows.single()["cgm_mgdl"])
        assertEquals("-0.3", rows.single()["sug_minDelta"])
    }

    @Test fun completedSixSampleRingRoundTrips() {
        val original = ring(0, 5, 10, 15, 20, 25)
        val encoded = codec.encode(original)!!
        assertEquals(6, encoded.size)
        val recovered = codec.decode(encoded)!!
        assertEquals(encoded, codec.encode(recovered))
        assertEquals(snapshot(25), recovered.lagged(0))
        assertEquals(snapshot(0), recovered.lagged(5))
    }

    @Test fun oneMinuteCadenceDoesNotPretendToProvideSixFiveMinuteLags() {
        val original = ring(0, 1, 2, 3)
        val rows = codec.encode(original)!!
        assertEquals(1, rows.size)
        assertEquals(start + 3 * minute,
            codec.decode(rows)!!.lagged(0)!!.timestampMs)
    }

    @Test fun coldRestartOnlyReplaysAvailableHistoricalLags() {
        val original = ring(0, 5, 10)
        val restored = codec.decode(codec.encode(original)!!)!!
        assertEquals(3, restored.size)
        assertNull(restored.lagged(4))
    }

    @Test fun aLongGapDiscardsStaleHistoricalLagData() {
        val original = ring(0, 5)
        val rows = codec.encode(original)!!.toMutableList()
        rows += codec.encode(ring(55))!!.single()
        val rebuilt = codec.decode(rows)!!
        assertEquals(1, rebuilt.size)
        assertEquals(start + 55 * minute, rebuilt.lagged(0)!!.timestampMs)
    }

    @Test fun malformedMissingFieldRejectsEntireSnapshot() {
        val source = codec.encode(ring(0, 5))!!
        assertNull(codec.decode(listOf(source[0], source[1] - "iob_iob")))
        assertNull(codec.decode(listOf(source[0] - "ts")))
        assertNull(codec.decode(listOf(source[0] - "sug_minDelta")))
    }

    @Test fun nanAndInfiniteNumbersCannotBecomeModelEvidence() {
        val source = codec.encode(ring(0))!!.single()
        assertNull(codec.decode(listOf(source + ("iob_iob" to "NaN"))))
        assertNull(codec.decode(listOf(source + ("cgm_mgdl" to "Infinity"))))
        assertNull(codec.decode(listOf(source + ("sug_eventualBG" to "invalid"))))
    }

    @Test fun outOfOrderAndDuplicateTimestampsAreRejected() {
        val source = codec.encode(ring(0, 5))!!
        assertNull(codec.decode(source.reversed()))
        assertNull(codec.decode(listOf(source[0], source[0])))
        assertNull(codec.decode(listOf(source[0] + ("ts" to "0"))))
    }

    @Test fun oversizedPersistedArrayCannotBeUsed() {
        val one = codec.encode(ring(0))!!.single()
        assertNull(codec.decode(List(7) { index ->
            one + ("ts" to (start + index * 5 * minute).toString())
        }))
    }

    @Test fun unknownFutureFieldsDoNotChangeRequiredModelInputs() {
        val source = codec.encode(ring(0))!!.single()
        val recovered = codec.decode(listOf(source + ("futureField" to "later")))!!
        assertEquals(snapshot(0), recovered.lagged(0))
    }

    @Test fun stringMapsAreNotUsedAsMutablePreferenceStorage() {
        val source = codec.encode(ring(0))!!
        val edited = source[0] + ("cgm_mgdl" to "190.0")
        assertEquals("110.0", source[0]["cgm_mgdl"])
        assertEquals(190.0, codec.decode(listOf(edited))!!.lagged(0)!!.cgmMgdl)
    }
}
