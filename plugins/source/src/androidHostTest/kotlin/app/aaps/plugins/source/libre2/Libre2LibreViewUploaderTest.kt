package app.aaps.plugins.source.libre2

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Libre2LibreViewUploaderTest {
    @get:Rule val folder = TemporaryFolder()
    private val now = 1_800_000_000_000L
    private val sensor = "F61D6D0600A007E0"
    private fun reading(id: Int, sensorId: String = sensor, offset: Long = 0) =
        Libre2Reading(sensorId, id, now - 60_000_000 + id * 60_000L + offset, now - 86_400_000, 110, 3)

    private inner class Store : Libre2UploadStore {
        var value = Libre2ExchangeSettings(libreView = true, email = "test@example.invalid", password = "dummy",
            baseUrl = "https://api.libreview.ru/lsl", token = "token", registeredSensor = sensor)
        val queue = Libre2UploadQueue(File(folder.root, "queue.json"))
        var onBatch: (() -> Unit)? = null
        var acknowledgements = 0
        override fun settings() = value
        override fun update(change: (Libre2ExchangeSettings) -> Libre2ExchangeSettings): Libre2ExchangeSettings {
            value = change(value)
            return value
        }
        override fun batch(now: Long): List<Libre2UploadRecord> {
            val result = queue.batch(now)
            onBatch?.also { onBatch = null; it() }
            return result
        }
        override fun acknowledge(batch: List<Libre2UploadRecord>, revision: String, now: Long) {
            if (value.libreView && value.revision == revision) {
                acknowledgements++
                queue.acknowledge(batch)
                value = value.copy(lastUpload = now, tokenRefreshes = 0, status = "uploaded")
            }
        }
    }

    @Test fun `disabled or blocked exports never contact the server`() = runBlocking<Unit> {
        val store = Store()
        val api = Libre2LibreViewApi { _, _, _ -> fail("Unexpected HTTP request"); JSONObject() }
        store.value = store.value.copy(libreView = false)
        assertEquals(Libre2LibreViewUploader.Outcome.SUCCESS, Libre2LibreViewUploader(store, api) { now }.run())
        store.value = store.value.copy(libreView = true, authBlocked = true)
        assertEquals(Libre2LibreViewUploader.Outcome.SUCCESS, Libre2LibreViewUploader(store, api) { now }.run())
    }

    @Test fun `disabling during batch load stops the already registered sensor upload`() = runBlocking<Unit> {
        val store = Store()
        store.queue.add(reading(8), now)
        store.onBatch = { store.value = store.value.copy(libreView = false, revision = "disabled") }
        val api = Libre2LibreViewApi { _, _, _ -> fail("Upload after disable"); JSONObject() }
        assertEquals(Libre2LibreViewUploader.Outcome.SUCCESS, Libre2LibreViewUploader(store, api) { now }.run())
        assertEquals(0, store.acknowledgements)
    }

    @Test fun `disabling during config fetch prevents sending credentials`() = runBlocking<Unit> {
        val store = Store()
        store.value = store.value.copy(token = "")
        var calls = 0
        val api = Libre2LibreViewApi { _, _, _ ->
            calls++
            store.value = store.value.copy(libreView = false, revision = "disabled")
            JSONObject().put("newYuUrl", "https://api.libreview.ru/lsl")
        }
        assertEquals(Libre2LibreViewUploader.Outcome.SUCCESS, Libre2LibreViewUploader(store, api) { now }.run())
        assertEquals(1, calls)
        assertEquals("", store.value.token)
    }

    @Test fun `account change during sensor registration prevents measurement upload`() = runBlocking<Unit> {
        val store = Store()
        store.queue.add(reading(8), now)
        store.value = store.value.copy(registeredSensor = "")
        var calls = 0
        val api = Libre2LibreViewApi { _, method, _ ->
            calls++
            assertEquals("PUT", method)
            store.value = store.value.copy(revision = "replacement", token = "new-token")
            JSONObject().put("status", 0)
        }
        assertEquals(Libre2LibreViewUploader.Outcome.SUCCESS, Libre2LibreViewUploader(store, api) { now }.run())
        assertEquals(1, calls)
        assertEquals("", store.value.registeredSensor)
        assertEquals(0, store.acknowledgements)
    }

    @Test fun `account change during measurement request does not acknowledge its old queue`() = runBlocking<Unit> {
        val store = Store()
        (8..22).forEach { store.queue.add(reading(it), now) }
        val api = Libre2LibreViewApi { _, _, _ ->
            store.value = store.value.copy(revision = "replacement", token = "new-token")
            JSONObject().put("status", 0)
        }
        assertEquals(Libre2LibreViewUploader.Outcome.SUCCESS, Libre2LibreViewUploader(store, api) { now }.run())
        assertEquals(0, store.acknowledgements)
        assertEquals(15, store.queue.batch(now).size)
    }

    @Test fun `cancellation during config fetch stops subsequent requests`() = runBlocking<Unit> {
        val store = Store()
        store.value = store.value.copy(token = "")
        val job = Job()
        var calls = 0
        val api = Libre2LibreViewApi { _, _, _ ->
            calls++
            job.cancel()
            JSONObject().put("newYuUrl", "https://api.libreview.ru/lsl")
        }
        try {
            runBlocking(job) { Libre2LibreViewUploader(store, api) { now }.run() }
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
        assertEquals(1, calls)
    }

    @Test fun `all offline records including a short final batch are drained in one run`() = runBlocking<Unit> {
        val store = Store()
        (8..320).forEach { store.queue.add(reading(it), now) }
        val ids = mutableSetOf<Int>()
        var requests = 0
        val api = Libre2LibreViewApi { _, _, body ->
            requests++
            val entries = body!!.getJSONObject("DeviceData").getJSONObject("measurementLog").getJSONArray("currentGlucoseEntries")
            repeat(entries.length()) { ids += entries.getJSONObject(it).getInt("recordNumber") }
            JSONObject().put("status", 0)
        }
        assertEquals(Libre2LibreViewUploader.Outcome.SUCCESS, Libre2LibreViewUploader(store, api) { now }.run())
        assertEquals(2, requests)
        assertEquals(313, ids.size)
        assertTrue(store.queue.batch(now).size < 15)
    }

    @Test fun `partial old sensor does not defer a new sensor until another minute`() = runBlocking<Unit> {
        val store = Store()
        store.queue.add(reading(8), now)
        store.queue.add(reading(8, "E11D6D0600A007E0", 60_000), now)
        var uploads = 0
        val api = Libre2LibreViewApi { _, method, _ ->
            if (method == "POST") uploads++
            JSONObject().put("status", 0)
        }
        assertEquals(Libre2LibreViewUploader.Outcome.SUCCESS, Libre2LibreViewUploader(store, api) { now }.run())
        assertEquals(2, uploads)
    }

    @Test fun `server lockout longer than fifteen minutes is persisted and respected`() = runBlocking<Unit> {
        val store = Store()
        store.value = store.value.copy(token = "")
        var requests = 0
        val api = Libre2LibreViewApi { _, _, _ ->
            requests++
            if (requests == 1) JSONObject().put("newYuUrl", "https://api.libreview.ru/lsl") else
                JSONObject().put("status", 429).put("data", JSONObject().put("data", JSONObject().put("lockout", 3600)))
        }
        val uploader = Libre2LibreViewUploader(store, api) { now }
        assertEquals(Libre2LibreViewUploader.Outcome.RETRY, uploader.run())
        assertEquals(now + 3_600_000, store.value.retryAfter)
        assertEquals(Libre2LibreViewUploader.Outcome.RETRY, uploader.run())
        assertEquals(2, requests)
    }

    @Test fun `wrong password blocks repeated logins until settings change`() = runBlocking<Unit> {
        val store = Store()
        store.value = store.value.copy(token = "")
        var requests = 0
        val api = Libre2LibreViewApi { _, _, _ ->
            requests++
            if (requests == 1) JSONObject().put("newYuUrl", "https://api.libreview.ru/lsl") else
                JSONObject().put("status", 20).put("reason", "invalidPassword")
        }
        val uploader = Libre2LibreViewUploader(store, api) { now }
        assertEquals(Libre2LibreViewUploader.Outcome.SUCCESS, uploader.run())
        assertTrue(store.value.authBlocked)
        assertEquals(Libre2LibreViewUploader.Outcome.SUCCESS, uploader.run())
        assertEquals(2, requests)
    }

    @Test fun `a new rejected token cannot cause an endless authentication loop`() = runBlocking<Unit> {
        val store = Store()
        store.queue.add(reading(8), now)
        store.value = store.value.copy(tokenRefreshes = 1)
        val api = Libre2LibreViewApi { _, _, _ -> JSONObject().put("status", 20).put("reason", "wrongDeviceInToken") }
        Libre2LibreViewUploader(store, api) { now }.run()
        assertTrue(store.value.authBlocked)
        assertEquals("auth", store.value.status)
        assertEquals("", store.value.token)
    }
}
