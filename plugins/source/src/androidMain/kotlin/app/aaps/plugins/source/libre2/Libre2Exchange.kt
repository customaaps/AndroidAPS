package app.aaps.plugins.source.libre2

import android.content.Context
import android.content.Intent
import androidx.annotation.Keep
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Optional exports never participate in the sensor's Bluetooth or decoder state machine. */
internal class Libre2Exchange(private val context: Context) {
    private val store = Libre2ExchangeStore(context)
    fun settings() = store.settings()

    fun save(xdrip: Boolean, libreView: Boolean, email: String, password: String) {
        require(!libreView || email.isNotBlank() && password.isNotEmpty())
        store.update { old ->
            val changed = old.email != email.trim() || old.password != password || old.libreView != libreView
            old.copy(xdrip = xdrip, libreView = libreView, email = email.trim(), password = password,
                revision = if (changed) UUID.randomUUID().toString() else old.revision,
                token = if (changed) "" else old.token, baseUrl = if (changed) "" else old.baseUrl,
                accountId = if (changed) "" else old.accountId, registeredSensor = if (changed) "" else old.registeredSensor,
                authBlocked = if (changed) false else old.authBlocked, retryAfter = if (changed) 0 else old.retryAfter,
                tokenRefreshes = if (changed) 0 else old.tokenRefreshes,
                status = if (changed) "" else old.status)
        }
        schedule(context, libreView)
    }

    fun onReading(reading: Libre2Reading) {
        val s = store.settings()
        var failure: Exception? = null
        if (s.xdrip) {
            try { context.sendBroadcast(reading.xdripIntent()) }
            catch (error: Exception) { failure = error }
        }
        if (s.libreView) {
            try { store.enqueue(reading, System.currentTimeMillis()); enqueue(context) }
            catch (error: Exception) { failure = error }
        }
        failure?.let { throw it }
    }

    companion object {
        private const val WORK = "Libre2-LibreView-RU"
        private const val PERIODIC = "Libre2-LibreView-RU-periodic"
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        private fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<Libre2LibreViewWorker>().setConstraints(network)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
        fun schedule(context: Context, enabled: Boolean) {
            val manager = WorkManager.getInstance(context)
            if (enabled) {
                manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<Libre2LibreViewWorker>(15, TimeUnit.MINUTES).setConstraints(network)
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
                enqueue(context)
            } else {
                manager.cancelUniqueWork(WORK)
                manager.cancelUniqueWork(PERIODIC)
            }
        }
    }
}

/** Same local Sync Follower protocol as JugglucoNG's XdripFollowerSend. */
internal fun Libre2Reading.xdripIntent(): Intent {
    val direction = when (trend) {
        1 -> "DoubleDown"; 2 -> "SingleDown"; 3 -> "Flat"; 4 -> "SingleUp"; 5 -> "DoubleUp"; else -> "NOT_COMPUTABLE"
    }
    return Intent("info.nightscout.client.NEW_SGV").setPackage("com.eveningoutpost.dexdrip")
        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        .putExtra("sgv", JSONObject().put("mills", timestamp).put("mgdl", mgdl).put("direction", direction).toString())
}

/** Uses WorkManager's default constructor; no dependency on the decoder process or a pump. */
@Keep
class Libre2LibreViewWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        uploadLock.withLock {
            val store = Libre2ExchangeStore(applicationContext)
            var revision = ""
            try {
                var s = store.settings()
                revision = s.revision
                if (!s.libreView || s.authBlocked) return@withLock Result.success()
                if (s.retryAfter > System.currentTimeMillis()) return@withLock Result.retry()
                val api = Libre2LibreViewApi()
                if (s.token.isEmpty()) {
                    val authenticated = api.authenticate(s)
                    s = store.update { current ->
                        if (current.revision == revision) current.copy(baseUrl = authenticated.baseUrl,
                            token = authenticated.token, accountId = authenticated.accountId, registeredSensor = "",
                            authBlocked = false, retryAfter = 0) else current
                    }
                }
                if (!s.libreView || s.revision != revision) return@withLock Result.success()
                val batch = store.batch(System.currentTimeMillis())
                if (batch.isEmpty()) {
                    store.update { if (it.revision == revision) it.copy(status = "authenticated") else it }
                    return@withLock Result.success()
                }
                val sensorId = batch.first().reading.sensorId
                if (s.registeredSensor != sensorId) {
                    api.register(s, sensorId)
                    s = store.update { if (it.revision == revision) it.copy(registeredSensor = sensorId) else it }
                }
                if (!s.libreView || s.revision != revision) return@withLock Result.success()
                api.upload(s, batch, System.currentTimeMillis())
                // Changing credentials/disable during a request must not advance the new account's queue.
                store.acknowledge(batch, revision, System.currentTimeMillis())
                // Drain accumulated offline readings; retain only the last, incomplete history window.
                if (store.batch(System.currentTimeMillis()).size >= 300) Result.retry() else Result.success()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val known = failure as? LibreViewFailure
                // Never persist/log HTTP bodies, tokens, email or exception messages.
                runCatching {
                    store.update {
                        if (it.revision != revision) it else it.copy(
                            token = if (known?.reauthenticate == true) "" else it.token,
                            tokenRefreshes = if (known?.reauthenticate == true) it.tokenRefreshes + 1 else it.tokenRefreshes,
                            authBlocked = known != null && (!known.retryable || known.reauthenticate && it.tokenRefreshes >= 1),
                            retryAfter = if (known?.code == "rate_limited") System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15) else 0,
                            status = if (known?.reauthenticate == true && it.tokenRefreshes >= 1) "auth" else known?.code ?: "network")
                    }
                }
                if (known != null && !known.retryable) Result.success() else Result.retry()
            }
        }
    }
    companion object { private val uploadLock = Mutex() }
}
