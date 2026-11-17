package app.aaps.plugins.source.libre2

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal interface Libre2UploadStore {
    fun settings(): Libre2ExchangeSettings
    fun update(change: (Libre2ExchangeSettings) -> Libre2ExchangeSettings): Libre2ExchangeSettings
    fun batch(now: Long): List<Libre2UploadRecord>
    fun acknowledge(batch: List<Libre2UploadRecord>, revision: String, now: Long)
}

/** Checks current consent/session before each HTTP operation; networking never runs in the sensor process. */
internal class Libre2LibreViewUploader(
    private val store: Libre2UploadStore,
    private val api: Libre2LibreViewApi,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class Outcome { SUCCESS, RETRY }

    private suspend fun active(revision: String): Libre2ExchangeSettings? {
        currentCoroutineContext().ensureActive()
        return store.settings().takeIf { it.libreView && !it.authBlocked && it.revision == revision }
    }

    suspend fun run(): Outcome {
        var revision = ""
        try {
            var s = store.settings()
            revision = s.revision
            if (!s.libreView || s.authBlocked) return Outcome.SUCCESS
            if (s.email.isBlank() || s.password.isEmpty()) {
                store.update { current -> if (current.revision == revision && current.libreView)
                    current.copy(status = "credentials") else current }
                return Outcome.SUCCESS
            }
            if (s.retryAfter > now()) return Outcome.RETRY
            if (s.token.isEmpty()) {
                s = active(revision) ?: return Outcome.SUCCESS
                val context = currentCoroutineContext()
                val authenticated = api.authenticate(s) {
                    context.ensureActive()
                    val current = store.settings()
                    if (!current.libreView || current.authBlocked || current.revision != revision) throw SessionChanged()
                }
                store.update { current ->
                    if (current.revision == revision && current.libreView) current.copy(baseUrl = authenticated.baseUrl,
                        token = authenticated.token, accountId = authenticated.accountId, registeredSensor = "",
                        authBlocked = false, retryAfter = 0, status = "authenticated") else current
                }
            }
            repeat(MAX_BATCHES) {
                s = active(revision) ?: return Outcome.SUCCESS
                val batch = store.batch(now())
                if (batch.isEmpty()) {
                    store.update { current -> if (current.revision == revision && current.libreView &&
                        current.status in listOf("", "authenticated", "credentials"))
                        current.copy(status = "waiting_readings") else current }
                    return Outcome.SUCCESS
                }
                val sensorId = batch.first().reading.sensorId
                if (s.registeredSensor != sensorId) {
                    s = active(revision) ?: return Outcome.SUCCESS
                    api.register(s, sensorId)
                    store.update { current ->
                        if (current.revision == revision && current.libreView) current.copy(registeredSensor = sensorId) else current
                    }
                }
                // Includes the already-registered path: a switch during batch() must stop this request too.
                s = active(revision) ?: return Outcome.SUCCESS
                api.upload(s, batch, now())
                currentCoroutineContext().ensureActive()
                // Store checks the revision under its lock before acknowledging.
                store.acknowledge(batch, revision, now())
                active(revision) ?: return Outcome.SUCCESS
                val next = store.batch(now())
                val sent = batch.map { it.currentRecordNumber }.toSet()
                // A retained partial history window alone needs no repeat. Drain new records even
                // when the next batch has fewer than 300 entries or belongs to a replacement sensor.
                if (next.none { it.currentRecordNumber !in sent }) return Outcome.SUCCESS
            }
            return Outcome.RETRY
        } catch (_: SessionChanged) { return Outcome.SUCCESS }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val known = failure as? LibreViewFailure
            // Neither exception text nor server bodies (which may contain credentials) are persisted.
            store.update { current ->
                if (current.revision != revision || !current.libreView) current else current.copy(
                    token = if (known?.reauthenticate == true) "" else current.token,
                    tokenRefreshes = if (known?.reauthenticate == true) current.tokenRefreshes + 1 else current.tokenRefreshes,
                    authBlocked = known != null && (!known.retryable || known.reauthenticate && current.tokenRefreshes >= 1),
                    retryAfter = if (known?.code == "rate_limited") now() + known.retryDelayMillis else current.retryAfter,
                    status = if (known?.reauthenticate == true && current.tokenRefreshes >= 1) "auth" else known?.code ?: "network")
            }
            return if (known != null && !known.retryable) Outcome.SUCCESS else Outcome.RETRY
        }
    }

    private class SessionChanged : Exception()
    companion object { private const val MAX_BATCHES = 4 }
}
