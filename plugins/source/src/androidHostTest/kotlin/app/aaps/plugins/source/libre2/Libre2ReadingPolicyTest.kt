package app.aaps.plugins.source.libre2

import app.aaps.core.data.model.TrendArrow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Libre2ReadingPolicyTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `invalid quality and stale or future readings never enter APS`() {
        assertTrue(Libre2ReadingPolicy.accepts(now, 35, 0, now))
        assertTrue(Libre2ReadingPolicy.accepts(now - 60_000, 110, 0, now))
        assertFalse(Libre2ReadingPolicy.accepts(now, 110, 1, now))
        assertFalse(Libre2ReadingPolicy.accepts(now, 0, 0, now))
        assertFalse(Libre2ReadingPolicy.accepts(now, 1001, 0, now))
        assertFalse(Libre2ReadingPolicy.accepts(now + 60_001, 110, 0, now))
        assertFalse(Libre2ReadingPolicy.accepts(now - 86_400_001, 110, 0, now))
        assertFalse(Libre2ReadingPolicy.accepts(0, 110, 0, now))
    }

    @Test
    fun `sensor registration ignores old and duplicate starts with xDrip tolerance`() {
        val start = now - 86_400_000
        assertEquals(start, Libre2ReadingPolicy.sensorInsertionTime(start, null, now))
        assertNull(Libre2ReadingPolicy.sensorInsertionTime(start, start, now))
        assertNull(Libre2ReadingPolicy.sensorInsertionTime(start + 300_000, start, now))
        assertEquals(start + 300_001, Libre2ReadingPolicy.sensorInsertionTime(start + 300_001, start, now))
        assertNull(Libre2ReadingPolicy.sensorInsertionTime(start - 1, start, now))
        assertNull(Libre2ReadingPolicy.sensorInsertionTime(now + 1, null, now))
        assertNull(Libre2ReadingPolicy.sensorInsertionTime(0, null, now))
        assertNull(Libre2ReadingPolicy.sensorInsertionTime(now - 31L * 86_400_000, null, now))
    }

    @Test
    fun `vendor trend codes map to APS directions without reversing arrows`() {
        assertEquals(listOf(TrendArrow.NONE, TrendArrow.DOUBLE_DOWN, TrendArrow.SINGLE_DOWN,
            TrendArrow.FLAT, TrendArrow.SINGLE_UP, TrendArrow.DOUBLE_UP),
            (0..5).map(Libre2ReadingPolicy::trend))
        assertEquals(TrendArrow.NONE, Libre2ReadingPolicy.trend(255))
    }

    @Test
    fun `sensor Bluetooth address uses reversed NFC byte order`() {
        assertEquals("F6:E5:D4:C3:B2:A1", Libre2NfcReader.mac(byteArrayOf(0xa1.toByte(),
            0xb2.toByte(), 0xc3.toByte(), 0xd4.toByte(), 0xe5.toByte(), 0xf6.toByte())))
        assertThrows(IllegalArgumentException::class.java) { Libre2NfcReader.mac(ByteArray(5)) }
    }
}
