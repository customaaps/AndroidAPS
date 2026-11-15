package app.aaps.plugins.source.libre2

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Not exported in APS preferences/backups. Only the main process accesses this store. */
internal data class Libre2ExchangeSettings(
    val xdrip: Boolean = false,
    val libreView: Boolean = false,
    val email: String = "",
    val password: String = "",
    val deviceId: String = UUID.randomUUID().toString(),
    val revision: String = UUID.randomUUID().toString(),
    val baseUrl: String = "",
    val token: String = "",
    val accountId: String = "",
    val registeredSensor: String = "",
    val authBlocked: Boolean = false,
    val retryAfter: Long = 0,
    val lastUpload: Long = 0,
    val status: String = "",
    val tokenRefreshes: Int = 0,
) {
    fun withUserSettings(xdrip: Boolean, libreView: Boolean, email: String, password: String, now: Long): Libre2ExchangeSettings {
        val normalizedEmail = email.trim()
        require(!libreView || normalizedEmail.isNotEmpty() && password.isNotEmpty())
        val accountChanged = !this.email.equals(normalizedEmail, ignoreCase = true)
        val changed = accountChanged || this.password != password || this.libreView != libreView
        return copy(xdrip = xdrip, libreView = libreView, email = normalizedEmail, password = password,
            revision = if (changed) UUID.randomUUID().toString() else revision,
            token = if (changed) "" else token, baseUrl = if (changed) "" else baseUrl,
            accountId = if (changed) "" else accountId, registeredSensor = if (changed) "" else registeredSensor,
            authBlocked = if (changed) false else authBlocked,
            // An on/off toggle or password edit cannot bypass a server lockout for the same account.
            retryAfter = if (accountChanged) 0 else retryAfter,
            tokenRefreshes = if (changed) 0 else tokenRefreshes,
            lastUpload = if (accountChanged) 0 else lastUpload,
            status = when {
                !accountChanged && retryAfter > now -> "rate_limited"
                changed -> ""
                else -> status
            })
    }

    // Deliberately no generated toString containing credentials.
    override fun toString() = "Libre2ExchangeSettings(redacted)"
}

internal class Libre2ExchangeStore(context: Context) : Libre2UploadStore {
    private val directory = File(context.noBackupFilesDir, "libre2-exchange")
    private val settingsFile = Libre2AtomicFile(File(directory, "settings.enc"))
    private val queue = Libre2UploadQueue(File(directory, "upload.json"))

    override fun settings(): Libre2ExchangeSettings = synchronized(lock) { readSettings() }

    override fun update(change: (Libre2ExchangeSettings) -> Libre2ExchangeSettings) = synchronized(lock) {
        val old = readSettings()
        val updated = change(old)
        // Bind legacy queues before an email change; never send the old account's backlog to a new one.
        if (!old.email.equals(updated.email, ignoreCase = true) && old.email.isNotBlank())
            queue.forAccount(accountScope(old.email))
        writeSettings(updated)
        updated
    }

    fun enqueue(reading: Libre2Reading, now: Long) = synchronized(lock) {
        val s = readSettings()
        if (s.libreView) {
            queue.forAccount(accountScope(s.email))
            queue.add(reading, now)
        }
    }
    override fun batch(now: Long): List<Libre2UploadRecord> = synchronized(lock) {
        val s = readSettings()
        if (!s.libreView) emptyList() else {
            queue.forAccount(accountScope(s.email))
            queue.batch(now)
        }
    }
    override fun acknowledge(batch: List<Libre2UploadRecord>, revision: String, now: Long) = synchronized(lock) {
        val s = readSettings()
        if (s.revision == revision && s.libreView) {
            queue.acknowledge(batch)
            writeSettings(s.copy(lastUpload = now, retryAfter = 0, tokenRefreshes = 0, status = "uploaded"))
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun readSettings(): Libre2ExchangeSettings {
        if (!settingsFile.exists()) return Libre2ExchangeSettings()
        val data = settingsFile.read(64 * 1024)
        require(data.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        val json = JSONObject(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
        return Libre2ExchangeSettings(json.optBoolean("xdrip"), json.optBoolean("libreView"),
            json.getString("email"), json.getString("password"), json.getString("deviceId"), json.getString("revision"),
            json.optString("baseUrl"), json.optString("token"), json.optString("accountId"), json.optString("registeredSensor"),
            json.optBoolean("authBlocked"), json.optLong("retryAfter"), json.optLong("lastUpload"), json.optString("status"), json.optInt("tokenRefreshes"))
    }

    private fun writeSettings(s: Libre2ExchangeSettings) {
        val json = JSONObject().put("xdrip", s.xdrip).put("libreView", s.libreView).put("email", s.email)
            .put("password", s.password).put("deviceId", s.deviceId).put("revision", s.revision)
            .put("baseUrl", s.baseUrl).put("token", s.token).put("accountId", s.accountId)
            .put("registeredSensor", s.registeredSensor).put("authBlocked", s.authBlocked)
            .put("retryAfter", s.retryAfter).put("lastUpload", s.lastUpload).put("status", s.status).put("tokenRefreshes", s.tokenRefreshes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        check(cipher.iv.size == 12)
        settingsFile.write(cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8)))
    }

    companion object {
        private val lock = Any()
        private const val KEY_ALIAS = "AAPS.Libre2.LibreView.v1"
        private fun accountScope(email: String) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(email.trim().lowercase(java.util.Locale.ROOT).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

internal data class Libre2UploadRecord(val reading: Libre2Reading, val currentRecordNumber: Int)

/** Keeps uncompleted history windows after successful current-glucose uploads. */
internal class Libre2UploadQueue(path: File) {
    private val file = Libre2AtomicFile(path)
    private data class State(val nextId: Int = 1, val records: List<Libre2UploadRecord> = emptyList(), val accountScope: String = "")
    private fun read(): State {
        if (!file.exists()) return State()
        val json = JSONObject(String(file.read(4 * 1024 * 1024), Charsets.UTF_8))
        require(json.getInt("version") == 1)
        val array = json.getJSONArray("readings")
        val next = json.getInt("nextId")
        require(array.length() <= MAX_READINGS && next in 1..8_388_608)
        val records = List(array.length()) { index ->
            val r = array.getJSONArray(index)
            val reading = Libre2Reading(r.getString(0), r.getInt(1), r.getLong(2), r.getLong(3), r.getInt(4), r.getInt(5))
            val id = r.getInt(6)
            require(reading.valid() && id > 0 && id % 256 == 0 && id / 256 < next)
            Libre2UploadRecord(reading, id)
        }
        require(records.map { it.reading.identity }.distinct().size == records.size)
        require(records.map { it.currentRecordNumber }.distinct().size == records.size)
        return State(next, records, json.optString("accountScope"))
    }
    private fun write(state: State) {
        val array = JSONArray()
        state.records.forEach { record ->
            val r = record.reading
            array.put(JSONArray(listOf(r.sensorId, r.sampleId, r.timestamp, r.sensorStart, r.mgdl, r.trend, record.currentRecordNumber)))
        }
        file.write(JSONObject().put("version", 1).put("nextId", state.nextId)
            .put("readings", array).put("accountScope", state.accountScope).toString().toByteArray(Charsets.UTF_8))
    }
    fun forAccount(scope: String) {
        require(scope.matches(Regex("[a-f0-9]{64}")))
        val state = read()
        if (state.accountScope == scope) return
        write(state.copy(accountScope = scope, records = if (state.accountScope.isEmpty()) state.records else emptyList()))
    }

    fun add(reading: Libre2Reading, now: Long) {
        require(reading.valid())
        val state = read()
        val retained = state.records.filter { now - it.reading.timestamp <= RETENTION_MS }
        if (retained.any { it.reading.identity == reading.identity }) return
        check(retained.size < MAX_READINGS) { "LibreView upload queue is full" }
        check(state.nextId <= 8_388_607) { "LibreView current record counter exhausted" }
        write(state.copy(nextId = state.nextId + 1, records = (retained + Libre2UploadRecord(reading, state.nextId * 256)).sortedBy { it.reading.timestamp }))
    }
    fun batch(now: Long): List<Libre2UploadRecord> {
        val state = read()
        val retained = state.records.filter { now - it.reading.timestamp <= RETENTION_MS }
        if (state.records.size != retained.size) write(state.copy(records = retained))
        val sensor = retained.firstOrNull()?.reading?.sensorId ?: return emptyList()
        return retained.filter { it.reading.sensorId == sensor }.take(300)
    }
    fun acknowledge(batch: List<Libre2UploadRecord>) {
        val state = read()
        val oldSensor = batch.firstOrNull()?.reading?.sensorId?.let { it != state.records.lastOrNull()?.reading?.sensorId } == true
        // Old sensors cannot complete their final window; their genuine current values were uploaded.
        val readings = batch.map { it.reading }
        val completed = (if (oldSensor) readings else Libre2LibreViewPayload.completed(readings).flatten() + readings.filter { Libre2LibreViewPayload.bucket(it.sampleId) == 0 })
            .map { it.identity }.toSet()
        if (completed.isNotEmpty()) write(state.copy(records = state.records.filterNot { it.reading.identity in completed }))
    }
    companion object {
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_READINGS = 10_080
    }
}
