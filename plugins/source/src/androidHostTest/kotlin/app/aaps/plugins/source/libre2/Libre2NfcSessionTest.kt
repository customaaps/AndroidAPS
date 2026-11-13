package app.aaps.plugins.source.libre2

import java.io.IOException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Libre2NfcSessionTest {
    @Test
    fun `malformed and sensor-error responses cannot become patch info`() {
        for (response in listOf(byteArrayOf(), ByteArray(6), ByteArray(8), byteArrayOf(1, 0, 0, 0x39, 0, 0, 0))) {
            assertThrows(IOException::class.java) { Libre2NfcResponse.patchInfo(response) }
        }
    }

    @Test
    fun `response flag is stripped and returned patch info owns its bytes`() {
        val response = byteArrayOf(0, 1, 2, 0x39, 4, 5, 6)
        val info = Libre2NfcResponse.patchInfo(response)
        response.fill(0)
        assertArrayEquals(byteArrayOf(1, 2, 0x39, 4, 5, 6), info)
        assertEquals(Libre2Generation.SECOND, Libre2Generation.fromPatchInfo(info))
    }

    @Test
    fun `unknown regional profile retries second generation only after first failed`() {
        val attempts = mutableListOf<Int>()
        val unknown = ByteArray(6)
        assertTrue(Libre2Bootstrap.enable(unknown, { attempts.add(1); false }, { attempts.add(2); true }))
        assertEquals(listOf(1, 2), attempts)
        attempts.clear()
        assertTrue(Libre2Bootstrap.enable(unknown, { attempts.add(1); true }, { attempts.add(2); true }))
        assertEquals(listOf(1), attempts)
        assertFalse(Libre2Bootstrap.enable(unknown, { false }, { false }))
    }

    @Test
    fun `known generation never tries the other authentication path`() {
        val first = byteArrayOf(0, 0, 0x38, 0, 0, 0)
        val second = byteArrayOf(0, 0, 0x39, 0, 0, 0)
        assertFalse(Libre2Bootstrap.enable(first, { false }, { error("Unexpected Gen2 authentication") }))
        assertFalse(Libre2Bootstrap.enable(second, { error("Unexpected Gen1 authentication") }, { false }))
        assertThrows(IllegalArgumentException::class.java) { Libre2Bootstrap.enable(ByteArray(3), { true }, { true }) }
    }
}
