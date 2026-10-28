package app.aaps.plugins.boostport.aps

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.boostport.telemetry.AndroidHealthConnectReadOnlyAdapter
import dev.zacsweers.metro.*
import kotlinx.coroutines.*

/** Registration is leased only by the APS owner; no service, permission request or write permission is created. */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class AndroidBoostActivitySource(private val context: Context, private val persistence: PersistenceLayer,
    private val logger: AAPSLogger) : BoostApsActivitySource {
    private val lock = Any()
    private val tracker = BoostPhoneStepTracker()
    private var leases = 0
    private var registered = false
    private val sensors get() = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val listener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type != Sensor.TYPE_STEP_COUNTER) return
            val raw = event.values.firstOrNull() ?: return
            if (raw.isFinite() && raw >= 0f) tracker.accept(raw.toLong(), System.currentTimeMillis())
        }
    }
    private fun refreshRegistration() = synchronized(lock) {
        try {
        val allowed = context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        if (!allowed && registered) { sensors?.unregisterListener(listener); registered = false }
        if (allowed && leases > 0 && !registered) {
            val sensor = sensors?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if (sensor != null) registered = sensors?.registerListener(listener, sensor,
                SensorManager.SENSOR_DELAY_NORMAL, Handler(Looper.getMainLooper())) == true
        }
        } catch (error: Exception) {
            registered = false
            logger.warn(LTag.APS, "Boost phone step sensor unavailable: ${error.message}")
        }
    }
    override fun requestPermissions() {
        context.startActivity(Intent(context, BoostDataPermissionsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    override fun open(): BoostApsActivitySource.Lease {
        synchronized(lock) { leases++ }
        refreshRegistration()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val adapter = AndroidHealthConnectReadOnlyAdapter(context)
        val reader = object : BoostHeartRateReader {
            override suspend fun read(nowMs: Long, coveredMinutes: Set<Long>): BoostHeartRateReader.Result =
                when (val result = adapter.readHeartRate(nowMs, liveHrMinutes = coveredMinutes, preserveSourceSampleCadence = true)) {
                    is AndroidHealthConnectReadOnlyAdapter.ReadOutcome.Data -> BoostHeartRateReader.Result.Ready(
                        result.items.map { BoostHeartRateReader.Sample(it.timestampMs, it.beatsPerMinute, it.device) })
                    is AndroidHealthConnectReadOnlyAdapter.ReadOutcome.Blocked -> BoostHeartRateReader.Result.Unavailable(result.access.name)
                    is AndroidHealthConnectReadOnlyAdapter.ReadOutcome.Failed -> BoostHeartRateReader.Result.Unavailable(result.code)
                }
        }
        val ingest = BoostHealthConnectIngest(persistence, reader)
        return object : BoostApsActivitySource.Lease {
            private var closed = false
            private var ingestJob: Job? = null
            override fun snapshot(nowMs: Long, offsetMinutes: Int): BoostApsActivitySource.Snapshot {
                synchronized(lock) { if (closed) return BoostApsActivitySource.Snapshot() }
                refreshRegistration()
                return if (synchronized(lock) { registered }) tracker.snapshot(nowMs, offsetMinutes) else BoostApsActivitySource.Snapshot()
            }
            override fun healthConnect(enabled: Boolean, nowMs: Long, pollMinutes: Int) = synchronized(lock) {
                if (closed) return@synchronized
                if (!enabled) { ingestJob?.cancel(); ingestJob = null; return@synchronized }
                if (ingestJob?.isActive == true) return@synchronized
                ingestJob = scope.launch {
                    try { ingest.syncIfDue(nowMs, pollMinutes.coerceAtLeast(1)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { logger.warn(LTag.APS, "Boost HC backfill failed: ${error.message}") }
                }
            }
            override fun close() = synchronized(lock) {
                if (closed) return@synchronized
                closed = true; scope.cancel(); leases--
                if (leases == 0 && registered) { sensors?.unregisterListener(listener); registered = false }
            }
        }
    }
}
