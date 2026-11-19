package app.aaps.plugins.source.libre2

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Libre2ExchangeTest {
    @get:Rule val folder = TemporaryFolder()
    private val directory get() = folder.root
    private val now = 1_800_000_000_000L
    private fun reading(id: Int, sensor: String = "F61D6D0600A007E0", offset: Long = 0) =
        Libre2Reading(sensor, id, now - 3_600_000 + id * 60_000L + offset, now - 86_400_000, 100 + id, 3)

    @Test fun `queue keeps stable current IDs across restart and retries`() {
        val path = File(directory, "upload.json")
        val queue = Libre2UploadQueue(path)
        val r = reading(8)
        queue.add(r, now)
        queue.add(r.copy(timestamp = r.timestamp + 1000), now)
        val first = queue.batch(now)
        assertEquals(1, first.size)
        assertEquals(r, first.single().reading)
        assertEquals(first, Libre2UploadQueue(path).batch(now))
        queue.acknowledge(first)
        // An incomplete window is retained so a later reading can complete its history.
        assertEquals(first, queue.batch(now))
    }

    @Test fun `only complete fifteen minute windows become history`() {
        val queue = Libre2UploadQueue(File(directory, "upload.json"))
        (8..21).forEach { queue.add(reading(it), now) }
        assertTrue(Libre2LibreViewPayload.completed(queue.batch(now).map { it.reading }).isEmpty())
        queue.add(reading(22), now)
        val batch = queue.batch(now)
        val groups = Libre2LibreViewPayload.completed(batch.map { it.reading })
        assertEquals(1, groups.size)
        assertEquals((8..22).toList(), groups.single().map { it.sampleId })
        val payload = Libre2LibreViewPayload.measurements(batch, "test-token", "test-device", "model", "maker", 35, now)
        val log = payload.getJSONObject("DeviceData").getJSONObject("measurementLog")
        assertEquals(15, log.getJSONArray("currentGlucoseEntries").length())
        val history = log.getJSONArray("scheduledContinuousGlucoseEntries")
        assertEquals(115, history.getJSONObject(0).getInt("valueInMgPerDl"))
        assertEquals(Libre2LibreViewPayload.sensorMask(reading(8).sensorId) or 15, history.getJSONObject(0).getLong("recordNumber"))
        queue.acknowledge(batch)
        assertTrue(queue.batch(now).isEmpty())
        queue.add(reading(23), now)
        assertTrue(queue.batch(now).single().currentRecordNumber > batch.last().currentRecordNumber)
    }

    @Test fun `old partial window cannot block a replacement sensor`() {
        val queue = Libre2UploadQueue(File(directory, "upload.json"))
        queue.add(reading(8), now)
        queue.add(reading(8, "E11D6D0600A007E0", 60_000), now)
        val first = queue.batch(now)
        queue.acknowledge(first)
        val next = queue.batch(now)
        assertEquals("E11D6D0600A007E0", next.single().reading.sensorId)
        assertNotEquals(first.single().currentRecordNumber, next.single().currentRecordNumber)
    }

    @Test fun `expiry does not reset the current record counter`() {
        val queue = Libre2UploadQueue(File(directory, "upload.json"))
        queue.add(reading(8), now)
        val firstId = queue.batch(now).single().currentRecordNumber
        assertTrue(queue.batch(now + Libre2UploadQueue.RETENTION_MS + 1).isEmpty())
        val newer = reading(9).copy(timestamp = now + Libre2UploadQueue.RETENTION_MS)
        queue.add(newer, newer.timestamp)
        assertTrue(queue.batch(newer.timestamp).single().currentRecordNumber > firstId)
    }

    @Test fun `changing account scope clears old backlog while preserving record counter`() {
        val path = File(directory, "upload.json")
        val queue = Libre2UploadQueue(path)
        queue.forAccount("a".repeat(64))
        queue.add(reading(8), now)
        val old = queue.batch(now).single().currentRecordNumber
        val restored = Libre2UploadQueue(path)
        restored.forAccount("a".repeat(64))
        assertEquals(1, restored.batch(now).size)
        restored.forAccount("b".repeat(64))
        assertTrue(restored.batch(now).isEmpty())
        restored.add(reading(9), now)
        assertTrue(restored.batch(now).single().currentRecordNumber > old)
    }

    @Test fun `legacy queue can be bound to its existing account without losing data`() {
        val queue = Libre2UploadQueue(File(directory, "upload.json"))
        queue.add(reading(8), now)
        val original = queue.batch(now)
        queue.forAccount("a".repeat(64))
        assertEquals(original, queue.batch(now))
    }

    @Test fun `initial samples outside a history window are removed after confirmed upload`() {
        val queue = Libre2UploadQueue(File(directory, "upload.json"))
        (0..7).forEach { queue.add(reading(it), now) }
        queue.acknowledge(queue.batch(now))
        assertTrue(queue.batch(now).isEmpty())
    }

    @Test fun `HTTP retry after supports seconds and dates without shortening server cooldown`() {
        assertEquals(3_600_000L, LibreViewFailure.retryAfter("3600", now))
        val date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
            java.time.Instant.ofEpochMilli(now + 3_600_000).atZone(java.time.ZoneOffset.UTC))
        assertEquals(3_600_000L, LibreViewFailure.retryAfter(date, now))
        assertEquals(LibreViewFailure.MIN_RETRY_DELAY, LibreViewFailure.retryAfter("invalid", now))
        assertEquals(LibreViewFailure.MIN_RETRY_DELAY, LibreViewFailure.retryAfter("-1", now))
    }

    @Test fun `saving settings cannot bypass a cooldown for the same account`() {
        val original = Libre2ExchangeSettings(libreView = true, email = "test@example.invalid", password = "old",
            retryAfter = now + 3_600_000, status = "rate_limited")
        val disabled = original.withUserSettings(false, false, original.email, original.password, now)
        val enabled = disabled.withUserSettings(false, true, original.email, "corrected", now)
        assertEquals(original.retryAfter, enabled.retryAfter)
        assertEquals("rate_limited", enabled.status)
    }

    @Test fun `switching account resets its status and password correction preserves same account upload time`() {
        val original = Libre2ExchangeSettings(libreView = true, email = "test@example.invalid", password = "old",
            token = "old-token", authBlocked = true, lastUpload = now - 60_000, retryAfter = now + 3_600_000)
        val corrected = original.withUserSettings(false, true, original.email, "corrected", now)
        assertEquals(original.lastUpload, corrected.lastUpload)
        assertFalse(corrected.authBlocked)
        val switched = original.withUserSettings(false, true, "new@example.invalid", "new", now)
        assertEquals(0L, switched.lastUpload)
        assertEquals(0L, switched.retryAfter)
        assertEquals("", switched.token)
        assertNotEquals(original.revision, switched.revision)
    }

    @Test fun `changing local export leaves the cloud token and revision intact`() {
        val original = Libre2ExchangeSettings(libreView = true, email = "test@example.invalid", password = "old", token = "token")
        val changed = original.withUserSettings(true, true, original.email, original.password, now)
        assertEquals(original.token, changed.token)
        assertEquals(original.revision, changed.revision)
    }

    @Test fun `authentication resolves RU config and retries device registration once`() {
        val calls = mutableListOf<Triple<String, String, JSONObject?>>()
        val password = "quotes\" and slash\\"
        val api = Libre2LibreViewApi { address, method, body ->
            calls += Triple(address, method, body)
            when (calls.size) {
                1 -> JSONObject().put("newYuUrl", "https://api.libreview.ru")
                2 -> JSONObject().put("status", 20).put("reason", "wrongDeviceForUser")
                else -> JSONObject().put("status", 0).put("result", JSONObject().put("UserToken", "token").put("AccountId", "id"))
            }
        }
        val s = api.authenticate(Libre2ExchangeSettings(email = "test@example.invalid", password = password))
        assertEquals(Libre2LibreViewApi.CONFIG_RU, calls.first().first)
        assertFalse(calls[1].third!!.getBoolean("SetDevice"))
        assertTrue(calls[2].third!!.getBoolean("SetDevice"))
        assertEquals(password, JSONObject(calls[2].third.toString()).getString("Password"))
        assertEquals("id", s.accountId)
        assertEquals("token", s.token)
        assertFalse(s.toString().contains(password))
    }

    @Test fun `wrong password and rate limiting do not loop authentication`() {
        for (status in listOf(20, 429)) {
            var calls = 0
            val api = Libre2LibreViewApi { _, _, _ ->
                calls++
                if (calls == 1) JSONObject().put("newYuUrl", "https://api.libreview.ru")
                else JSONObject().put("status", status).put("reason", "invalidPassword")
            }
            val failure = assertThrows(LibreViewFailure::class.java) {
                api.authenticate(Libre2ExchangeSettings(email = "test@example.invalid", password = "dummy"))
            }
            assertEquals(status == 429, failure.retryable)
            assertEquals(2, calls)
        }
    }

    @Test fun `untrusted and insecure endpoints are rejected before sending credentials`() {
        listOf("http://api.libreview.ru", "https://libreview.ru.example.org",
            "https://test@libreview.ru", "https://libreview.ru:8443").forEach { address ->
            assertThrows(IllegalArgumentException::class.java) { Libre2LibreViewApi.validateBaseUrl(address) }
        }
    }

    @Test fun `local follower broadcast carries original timestamp and mgdl only to xDrip`() {
        val r = reading(8).copy(mgdl = 65, trend = 1)
        val intent = r.xdripIntent()
        assertEquals("info.nightscout.client.NEW_SGV", intent.action)
        assertEquals("com.eveningoutpost.dexdrip", intent.`package`)
        val sgv = JSONObject(requireNotNull(intent.getStringExtra("sgv")))
        assertEquals(r.timestamp, sgv.getLong("mills"))
        assertEquals(65, sgv.getInt("mgdl"))
        assertEquals("DoubleDown", sgv.getString("direction"))
    }

    @Test fun `local follower broadcast uses the available calibrated value`() {
        val reading = reading(8).copy(mgdl = 65)
        val intent = reading.xdripIntent(calibratedMgdl = 73.6)
        val sgv = JSONObject(requireNotNull(intent.getStringExtra("sgv")))
        assertEquals(74, sgv.getInt("mgdl"))
    }

    @Test fun `expired upload token requests a bounded reauthentication`() {
        val settings = Libre2ExchangeSettings(baseUrl = "https://api.libreview.ru", token = "expired")
        val api = Libre2LibreViewApi { _, _, _ -> JSONObject().put("status", 20).put("reason", "wrongDeviceInToken") }
        val failure = assertThrows(LibreViewFailure::class.java) { api.register(settings, reading(8).sensorId) }
        assertTrue(failure.reauthenticate)
        assertTrue(failure.retryable)
    }

    @Test fun `sensor serial and timestamps follow donor format and retry IDs stay unchanged`() {
        assertEquals("30000000000", Libre2LibreViewPayload.serial("00000000000007E0"))
        val record = Libre2UploadRecord(reading(8), 256)
        val first = Libre2LibreViewPayload.measurements(listOf(record), "token", "device", "model", "maker", 35, now)
        val second = Libre2LibreViewPayload.measurements(listOf(record), "token", "device", "model", "maker", 35, now + 60_000)
        fun current(p: JSONObject) = p.getJSONObject("DeviceData").getJSONObject("measurementLog").getJSONArray("currentGlucoseEntries").getJSONObject(0)
        assertEquals(current(first).toString(), current(second).toString())
        assertEquals(256, current(first).getInt("recordNumber"))
        assertTrue(current(first).getJSONObject("extendedProperties").getString("factoryTimestamp").endsWith(".000Z"))
        assertEquals(108, current(first).getInt("valueInMgPerDl"))
    }
}
