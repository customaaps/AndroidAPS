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
import java.util.concurrent.TimeUnit

/** Optional exports never participate in the sensor's Bluetooth or decoder state machine. */
internal class Libre2Exchange(private val context: Context) {
    private val store = Libre2ExchangeStore(context)
    fun settings() = store.settings()

    fun save(xdrip: Boolean, libreView: Boolean, email: String, password: String) {
        require(!libreView || email.isNotBlank() && password.isNotEmpty())
        store.update { old -> old.withUserSettings(xdrip, libreView, email, password, System.currentTimeMillis()) }
        schedule(context, libreView)
    }

    fun saveXdrip(enabled: Boolean) {
        store.update { old -> old.copy(xdrip = enabled) }
    }

    fun saveLibreView(enabled: Boolean, email: String, password: String) {
        require(!enabled || email.isNotBlank() && password.isNotEmpty())
        store.update { old -> old.withUserSettings(old.xdrip, enabled, email, password, System.currentTimeMillis()) }
        schedule(context, enabled)
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
            try {
                when (Libre2LibreViewUploader(Libre2ExchangeStore(applicationContext), Libre2LibreViewApi()).run()) {
                    Libre2LibreViewUploader.Outcome.SUCCESS -> Result.success()
                    Libre2LibreViewUploader.Outcome.RETRY -> Result.retry()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { Result.retry() } // Includes storage failures; never log credentials.
        }
    }
    companion object { private val uploadLock = Mutex() }
}
