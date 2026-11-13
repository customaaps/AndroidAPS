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
    fun `truncated corrupted and newer snapshots are rejected`() {
        val encoded = Libre2DecodeResult.fromNative(reply()).state.encode()
        assertThrows(Exception::class.java) { Libre2DecoderState.decode(encoded.copyOf(20)) }
        assertThrows(IllegalArgumentException::class.java) { Libre2DecoderState.decode(encoded + byteArrayOf(0)) }
        val unsupported = encoded.copyOf().also { it[3] = 2 }
        assertThrows(IllegalArgumentException::class.java) { Libre2DecoderState.decode(unsupported) }
        assertThrows(IllegalArgumentException::class.java) { Libre2DecoderState.fromNative(LongArray(5), arrayOf(byteArrayOf())) }
    }
}
