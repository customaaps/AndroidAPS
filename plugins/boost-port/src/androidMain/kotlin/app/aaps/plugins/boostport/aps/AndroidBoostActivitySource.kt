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
    private var tracker = BoostPhoneStepTracker()
    private var detector = BoostPhoneStepTracker()
    private var detectorCount = 0L
    private var detectorStarted = false
    private var detectorRegistered = false
    private var phoneStatus = "not-started"
    private var leases = 0
    private var registered = false
    private val sensors get() = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val listener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        override fun onSensorChanged(event: SensorEvent?) {
            event ?: return
            val now = System.currentTimeMillis()
            synchronized(lock) {
                if (leases <= 0) return
                when (event.sensor.type) {
                    Sensor.TYPE_STEP_COUNTER -> {
                        val raw = event.values.firstOrNull() ?: return
                        if (registered && raw.isFinite() && raw >= 0f) tracker.accept(raw.toLong(), now)
                    }
                    Sensor.TYPE_STEP_DETECTOR -> {
                        if (!detectorRegistered || event.values.firstOrNull() != 1f) return
                        if (!detectorStarted) { detector.accept(0, now); detectorStarted = true }
                        detector.accept(++detectorCount, now)
                    }
                    else -> Unit
                }
            }
        }
    }
    private fun refreshRegistration() = synchronized(lock) {
        try {
            val allowed = context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
            if (!allowed) {
                if (registered || detectorRegistered) sensors?.unregisterListener(listener)
                registered = false; detectorRegistered = false
                tracker = BoostPhoneStepTracker(); detector = BoostPhoneStepTracker()
                detectorCount = 0; detectorStarted = false
                phoneStatus = "permission-required"
                return@synchronized
            }
            if (leases <= 0) return@synchronized
            val manager = sensors
            val counter = manager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            val pulses = manager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            val handler = Handler(Looper.getMainLooper())
            if (!registered && counter != null) registered = manager?.registerListener(listener, counter,
                SensorManager.SENSOR_DELAY_NORMAL, 0, handler) == true
            // Some phones expose only the detector, or starve the cumulative counter.
            if (!detectorRegistered && pulses != null) detectorRegistered = manager?.registerListener(listener, pulses,
                SensorManager.SENSOR_DELAY_NORMAL, 0, handler) == true
            phoneStatus = when {
                counter == null && pulses == null -> "sensor-unavailable"
                !registered && !detectorRegistered -> "registration-failed"
                else -> "waiting-for-sensor"
            }
        } catch (error: Exception) {
            registered = false; detectorRegistered = false
            phoneStatus = "registration-failed"
            logger.warn(LTag.APS, "Boost phone step sensor unavailable: ${error.message}")
        }
    }

    private fun phoneSnapshot(nowMs: Long, offset: Int): BoostApsActivitySource.Snapshot = synchronized(lock) {
        val counter = if (registered) tracker.snapshot(nowMs, offset) else BoostApsActivitySource.Snapshot()
        val pulses = if (detectorRegistered) detector.snapshot(nowMs, offset) else BoostApsActivitySource.Snapshot()
        when {
            counter.phoneFeedLive && pulses.phoneFeedLive -> counter.copy(
                steps5 = maxOf(counter.steps5, pulses.steps5), steps15 = maxOf(counter.steps15, pulses.steps15),
                steps30 = maxOf(counter.steps30, pulses.steps30), steps60 = maxOf(counter.steps60, pulses.steps60),
                stepsToday = maxOf(counter.stepsToday, pulses.stepsToday), sourceStatus = "phone=counter+detector")
            counter.phoneFeedLive -> counter.copy(sourceStatus = "phone=step-counter")
            pulses.phoneFeedLive -> pulses.copy(sourceStatus = "phone=step-detector")
            else -> BoostApsActivitySource.Snapshot(sourceStatus = "phone=$phoneStatus")
        }
    }

    override fun requestPermissions() {
        context.startActivity(Intent(context, BoostDataPermissionsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    override fun open(): BoostApsActivitySource.Lease {
        synchronized(lock) {
            if (leases == 0) {
                tracker = BoostPhoneStepTracker(); detector = BoostPhoneStepTracker()
                detectorCount = 0; detectorStarted = false
            }
            leases++
        }
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
        val publisher = BoostStepCountPublisher(persistence)
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
            private var hcStatus = "not-requested"
            private var lastLiveRead: Long? = null

            init {
                // Persist independently of loop/CGM cadence, including while the settings screen is open.
                scope.launch {
                    while (isActive) {
                        val now = System.currentTimeMillis()
                        val offset = java.util.TimeZone.getDefault().getOffset(now) / 60_000
                        try {
                            refreshRegistration()
                            val phoneRows = synchronized(lock) {
                                if (closed) emptyList() else {
                                    val sample = phoneSnapshot(now, offset)
                                    when (sample.sourceStatus) {
                                        "phone=counter+detector" -> tracker.completedRows(now, offset) + detector.completedRows(now, offset)
                                        "phone=step-counter" -> tracker.completedRows(now, offset)
                                        "phone=step-detector" -> detector.completedRows(now, offset)
                                        else -> emptyList()
                                    }
                                }
                            }
                            publisher.publish(phoneRows, now)
                            // Live steps follow READ_STEPS permission, not the optional daily-shadow setting.
                            if (synchronized(lock) { !closed } &&
                                lastLiveRead?.let { now >= it && now - it < 5 * 60_000L } != true) {
                                lastLiveRead = now
                                when (val read = adapter.readLiveStepBuckets(now)) {
                                    is AndroidHealthConnectReadOnlyAdapter.ReadOutcome.Data -> {
                                        val rows = BoostStepCountPublisher.healthConnectRows(read.items.singleOrNull().orEmpty(), now, offset * 60_000L)
                                        publisher.publish(rows, now)
                                        val last = rows.maxOfOrNull { it.timestamp }
                                        synchronized(lock) { hcStatus = when {
                                            last == null -> "no-data"
                                            now - last > 12 * 60_000L -> "stale"
                                            else -> "live"
                                        } }
                                    }
                                    is AndroidHealthConnectReadOnlyAdapter.ReadOutcome.Blocked -> synchronized(lock) { hcStatus = read.access.name.lowercase() }
                                    is AndroidHealthConnectReadOnlyAdapter.ReadOutcome.Failed -> synchronized(lock) { hcStatus = read.code }
                                }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            synchronized(lock) { hcStatus = "read-or-persist-failed" }
                            logger.warn(LTag.APS, "Boost steps acquisition failed: ${error::class.simpleName}")
                        }
                        delay(60_000L)
                    }
                }
            }
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
                val phone = phoneSnapshot(nowMs, offsetMinutes)
                return phone.copy(stepsToday = maxOf(phone.stepsToday, (seed ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
                    sourceStatus = "${phone.sourceStatus}; health-connect=${synchronized(lock) { hcStatus }}")
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
                if (leases == 0 && (registered || detectorRegistered)) {
                    sensors?.unregisterListener(listener); registered = false; detectorRegistered = false
                }
            }
        }
    }
}

