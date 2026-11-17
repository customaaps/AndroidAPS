package app.aaps.plugins.source.libre2

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Libre2ExternalReadingTest {
    private val start = 1_800_000_000_000L
    private val now = start + 120 * 60_000
    private val pairing = Libre2Pairing(byteArrayOf(0xF6.toByte(), 0x1D, 0x6D, 6, 0, 0xA0.toByte(), 7, 0xE0.toByte()),
        ByteArray(6), "00:11:22:33:44:55", byteArrayOf(), start / 1000, false,
        Libre2DecoderState(sensorStartSeconds = start / 1000))
    private fun value() = GV(timestamp = now, value = 123.0, raw = null, noise = null,
        trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.LIBRE_2)

    @Test fun `external Libre2 data uses NFC identity and real measurement time`() {
        val reading = requireNotNull(externalLibre2Reading(value(), start, pairing, now))
        assertEquals("F61D6D0600A007E0", reading.sensorId)
        assertEquals(120, reading.sampleId)
        assertEquals(now, reading.timestamp)
        assertEquals(start, reading.sensorStart)
        assertEquals(123, reading.mgdl)
        assertTrue(reading.valid())
    }

    @Test fun `unidentified mismatched invalid and expired readings are not exported`() {
        assertNull(externalLibre2Reading(value(), start, null, now))
        assertNull(externalLibre2Reading(value(), 0, pairing, now))
        assertNull(externalLibre2Reading(value(), start + 600_000, pairing, now))
        assertNull(externalLibre2Reading(value().copy(isValid = false), start, pairing, now))
        assertNull(externalLibre2Reading(value().copy(sourceSensor = SourceSensor.UNKNOWN), start, pairing, now))
        assertNull(externalLibre2Reading(value().copy(value = Double.NaN), start, pairing, now))
        assertNull(externalLibre2Reading(value().copy(timestamp = now + 60_000), start, pairing, now))
        assertNull(externalLibre2Reading(value(), start, pairing, now + Libre2UploadQueue.RETENTION_MS + 1))
    }

    @Test fun `configuration toggle keeps credentials and server cooldown while invalidating in flight work`() {
        val old = Libre2ExchangeSettings(libreView = true, email = "user@example.invalid", password = "password",
            token = "token", accountId = "account", retryAfter = now + 900_000)
        val disabled = old.withUploadEnabled(false)
        assertFalse(disabled.libreView)
        assertNotEquals(old.revision, disabled.revision)
        assertEquals(old.token, disabled.token)
        assertEquals(old.password, disabled.password)
        assertEquals(old.accountId, disabled.accountId)
        assertEquals(old.retryAfter, disabled.retryAfter)
        val enabled = disabled.withUploadEnabled(true)
        assertTrue(enabled.libreView)
        assertNotEquals(disabled.revision, enabled.revision)
        assertEquals(old.accountId, enabled.accountId)
    }
}
