package app.aaps.plugins.source.libre2

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Libre2DecoderStateTest {
    private fun reply(status: Long = 0, quality: Long = 0, removed: Long = 0, glucose: Long = 110): Array<Any> = arrayOf(
        longArrayOf(status, 1_700_000_000, 1_700_000_060, 60, 20_160, 0, glucose, quality, 1, 3, removed),
        floatArrayOf(Float.NaN),
        arrayOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
    )

    @Test
    fun `opaque state survives restart without changing bytes or metadata`() {
        val state = Libre2DecodeResult.fromNative(reply()).state
        val restored = Libre2DecoderState.decode(state.encode())
        assertArrayEquals(state.metadata(), restored.metadata())
        state.arrays.zip(restored.arrays).forEach { (a, b) -> assertArrayEquals(a, b) }
    }

    @Test
    fun `errors bad quality and removed sensor cannot publish a value`() {
        assertTrue(Libre2DecodeResult.fromNative(reply()).canPublish)
        assertTrue(Libre2DecodeResult.fromNative(reply(glucose = 35)).canPublish)
        assertFalse(Libre2DecodeResult.fromNative(reply(status = 1)).canPublish)
        assertFalse(Libre2DecodeResult.fromNative(reply(quality = 1)).canPublish)
        assertFalse(Libre2DecodeResult.fromNative(reply(removed = 1)).canPublish)
        assertFalse(Libre2DecodeResult.fromNative(reply(glucose = 0)).canPublish)
    }

    @Test
    fun `rejected results retain the vendor glucose and identify the failed check`() {
        val cases = listOf(
            reply(status = 1) to "vendor_status",
            reply(quality = 1) to "quality",
            reply(removed = 1) to "sensor_removed",
            reply(glucose = 0) to "glucose_range",
            reply(glucose = 1001) to "glucose_range",
        )
        cases.forEach { (native, reason) ->
            val result = Libre2DecodeResult.fromNative(native)
            assertFalse(result.canPublish)
            assertEquals(reason, result.publicationRejection)
            assertEquals((native[0] as LongArray)[6].toInt(), result.glucoseMgdl)
            assertTrue(result.diagnosticSummary().contains("rejection=$reason"))
        }
        val valid = Libre2DecodeResult.fromNative(reply(glucose = 35))
        assertTrue(valid.canPublish)
        assertNull(valid.publicationRejection)
        assertTrue(valid.diagnosticSummary().contains("glucoseMgdl=35"))
    }

    @Test
    fun `invalid sensor times are distinguishable from an invalid glucose result`() {
        val result = Libre2DecodeResult.fromNative(reply())
        val noStart = result.copy(state = result.state.copy(sensorStartSeconds = 0))
        assertEquals("sensor_start", noStart.publicationRejection)
        val futureStart = result.copy(state = result.state.copy(sensorStartSeconds = 1_900_000_000))
        assertEquals("algorithm_time", futureStart.publicationRejection)
        assertFalse(futureStart.canPublish)
        assertTrue(futureStart.diagnosticSummary().contains("sensorStartSeconds=1900000000"))
        assertTrue(futureStart.diagnosticSummary().contains("algorithmEndSeconds=1700000060"))
    }

    @Test
    fun `truncated corrupted and newer snapshots are rejected`() {
        val encoded = Libre2DecodeResult.fromNative(reply()).state.encode()
        assertThrows(Exception::class.java) { Libre2DecoderState.decode(encoded.copyOf(20)) }
        assertThrows(IllegalArgumentException::class.java) { Libre2DecoderState.decode(encoded + byteArrayOf(0)) }
        val unsupported = encoded.copyOf().also { it[3] = 2 }
        assertThrows(IllegalArgumentException::class.java) { Libre2DecoderState.decode(unsupported) }
        assertThrows(IllegalArgumentException::class.java) { Libre2DecoderState.fromNative(LongArray(5), arrayOf(byteArrayOf())) }
    }
}
