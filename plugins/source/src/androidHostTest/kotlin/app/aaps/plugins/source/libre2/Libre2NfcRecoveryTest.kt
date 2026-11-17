package app.aaps.plugins.source.libre2

import java.io.IOException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Libre2NfcRecoveryTest {
    @Test
    fun `unknown generation retries the whole read and decode after first protocol failure`() {
        val calls = mutableListOf<String>()
        val result = readLibre2Generation(null,
            first = { calls += "first read"; throw IOException("encrypted FRAM") },
            second = { calls += "second read"; "decoded" })
        assertEquals(Libre2Generation.SECOND to "decoded", result)
        assertEquals(listOf("first read", "second read"), calls)
    }

    @Test
    fun `unknown generation keeps successful first protocol and both failures remain visible`() {
        assertEquals(Libre2Generation.FIRST to "decoded",
            readLibre2Generation(null, { "decoded" }, { error("Unexpected fallback") }))
        val first = IOException("first failed")
        val second = IOException("second failed")
        val failure = assertThrows(IOException::class.java) {
            readLibre2Generation(null, { throw first }, { throw second })
        }
        assertSame(second, failure)
        assertArrayEquals(arrayOf(first), failure.suppressed)
    }

    @Test
    fun `known generation does not attempt another protocol`() {
        val failure = IOException("read failed")
        assertSame(failure, assertThrows(IOException::class.java) {
            readLibre2Generation(Libre2Generation.FIRST, { throw failure }, { error("Unexpected fallback") })
        })
        assertEquals(Libre2Generation.SECOND to "decoded",
            readLibre2Generation(Libre2Generation.SECOND, { error("Unexpected first protocol") }, { "decoded" }))
    }

    @Test
    fun `pairing recovery rejects activation before any NFC command`() {
        var commands = 0
        val reader = Libre2NfcReader { commands++; error("Unexpected NFC command") }
        assertThrows(IllegalArgumentException::class.java) {
            reader.read(ByteArray(8), activate = true, nowSeconds = 100, previous = null, repairPairing = true)
        }
        assertEquals(0, commands)
    }

    private fun pairing(uid: Byte = 1) = Libre2Pairing(
        ByteArray(8) { uid }, ByteArray(6), "old", byteArrayOf(1),
        100, true, Libre2DecoderState(), unlockCounter = 42, lastSampleId = 123, lastReadingMillis = 456_000)

    @Test
    fun `repair keeps delivery checkpoint but replaces credentials and resets unlock counter`() {
        val old = pairing()
        val renewed = old.copy(address = "new", auth = byteArrayOf(2), enabledAtSeconds = 200,
            unlockCounter = 0, lastSampleId = -1, lastReadingMillis = 0).withDeliveryCheckpoint(old)
        assertEquals("new", renewed.address)
        assertArrayEquals(byteArrayOf(2), renewed.auth)
        assertEquals(200L, renewed.enabledAtSeconds)
        assertEquals(0, renewed.unlockCounter)
        assertEquals(123, renewed.lastSampleId)
        assertEquals(456_000L, renewed.lastReadingMillis)
        assertEquals(-1, pairing(2).copy(lastSampleId = -1).withDeliveryCheckpoint(old).lastSampleId)
    }
}
