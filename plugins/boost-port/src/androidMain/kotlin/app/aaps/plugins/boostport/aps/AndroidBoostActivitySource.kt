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
        val stepHistory = BoostHealthConnectStepHistory(object : BoostStepHistoryReader {
            override suspend fun read(nowMs: Long): BoostStepHistoryReader.Result = when (val result = adapter.readStepIntervals(nowMs)) {
                is AndroidHealthConnectReadOnlyAdapter.ReadOutcome.Data -> BoostStepHistoryReader.Result.Ready(result.items.map {
                    BoostStepHistoryReader.Sample(it.dataOrigin, it.startMs, it.endMs, it.stepCount, it.phoneDevice)
                })
                is AndroidHealthConnectReadOnlyAdapter.ReadOutcome.Blocked -> BoostStepHistoryReader.Result.Unavailable(result.access.name)
                is AndroidHealthConnectReadOnlyAdapter.ReadOutcome.Failed -> BoostStepHistoryReader.Result.Unavailable(result.code)
            }
        })
        return object : BoostApsActivitySource.Lease {
            private var closed = false
            private var ingestJob: Job? = null
            private var stepsJob: Job? = null
            private var stepsEnabled = false
            override fun stepHistorySnapshot(nowMs: Long, offsetMinutes: Int): BoostStepHistorySnapshot? = synchronized(lock) {
                if (closed || !stepsEnabled) null else stepHistory.snapshot(nowMs, offsetMinutes)
            }
            override fun stepHistory(enabled: Boolean, nowMs: Long, offsetMinutes: Int) = synchronized(lock) {
                if (closed) return@synchronized
                stepsEnabled = enabled
                if (!enabled) { stepsJob?.cancel(); stepsJob = null; stepHistory.clear(); return@synchronized }
                if (stepsJob?.isActive == true) return@synchronized
                stepsJob = scope.launch {
                    try { stepHistory.syncIfDue(nowMs, offsetMinutes) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { stepHistory.clear(); logger.warn(LTag.APS, "Boost HC step history failed: ${error.message}") }
                }
            }
            override fun snapshot(nowMs: Long, offsetMinutes: Int): BoostApsActivitySource.Snapshot {
                synchronized(lock) { if (closed) return BoostApsActivitySource.Snapshot() }
                refreshRegistration()
                val hc = stepHistorySnapshot(nowMs, offsetMinutes)
                val today = (nowMs + offsetMinutes * 60_000L) / 86_400_000L
                val seed = hc?.takeIf { it.todayIndex == today }?.let { it.chosenSource?.let { source -> it.perSourceDay[source]?.get(today) } }
                seed?.let { tracker.seedToday(today, it) }
                return if (synchronized(lock) { registered }) tracker.snapshot(nowMs, offsetMinutes)
                else BoostApsActivitySource.Snapshot(stepsToday = (seed ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
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
                closed = true; scope.cancel(); stepHistory.clear(); leases--
                if (leases == 0 && registered) { sensors?.unregisterListener(listener); registered = false }
            }
        }
    }
}

