package app.aaps.plugins.source.libre2

import android.content.*
import android.os.*
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The main process only accepts validated measurements; the vendor decoder never runs here. */
@Inject
@SingleIn(AppScope::class)
class Libre2Client(
    private val context: Context,
    private val persistenceLayer: PersistenceLayer,
    private val rxBus: RxBus,
    private val logger: AAPSLogger,
    private val preferences: Preferences,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Mutex()
    private val exchange by lazy { Libre2Exchange(context.applicationContext) }
    private val mutableReadings = MutableStateFlow<List<Libre2Reading>>(emptyList())
    internal val recentReadings = mutableReadings.asStateFlow()
    private val mutableStatus = MutableStateFlow(Libre2Status())
    internal val status = mutableStatus.asStateFlow()
    @Volatile private var enabled = false
    @Volatile private var generation = 0
    private var connection: DecoderConnection? = null
    private var crashes = 0
    private val rebind = Runnable { if (enabled && connection == null) bind() }

    /** A separate receiver per binding rejects queued replies from an obsolete service session. */
    private inner class DecoderConnection(val epoch: Int) : ServiceConnection {
        val sessionId = java.util.UUID.randomUUID().toString()
        var bound = false
        var remote: Messenger? = null
        val inFlight = mutableSetOf<String>()
        val receiver = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (!enabled || connection !== this@DecoderConnection || message.sendingUid != android.os.Process.myUid()) return
                when (message.what) {
                    Libre2Ipc.STATUS -> {
                        val code = Libre2StatusCode.entries.getOrNull(message.data.getInt("code")) ?: return
                        mutableStatus.value = Libre2Status(code, message.data.getBoolean("nativeReady"),
                            message.data.getString("detail").orEmpty(), message.data.getLong("lastReading"))
                        if (code == Libre2StatusCode.RECEIVING) crashes = 0
                    }
                    Libre2Ipc.READING -> accept(message.data, this@DecoderConnection)
                }
            }
        })
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (!enabled || connection !== this) return
            remote = Messenger(service)
            send(Libre2Ipc.REGISTER)
        }
        override fun onServiceDisconnected(name: ComponentName) = disconnected(this)
        override fun onBindingDied(name: ComponentName) = disconnected(this)
        override fun onNullBinding(name: ComponentName) = disconnected(this)

        fun send(what: Int, data: Bundle = Bundle()) {
            data.putString("session", sessionId)
            try { remote?.send(Message.obtain(null, what).apply { replyTo = receiver; this.data = data }) }
            catch (error: RemoteException) { disconnected(this) }
        }
        fun unbind() {
            remote = null
            if (bound) { bound = false; runCatching { context.unbindService(this) } }
        }
    }

    internal suspend fun start() = withContext(Dispatchers.Main.immediate) {
        if (!enabled) {
            enabled = true; crashes = 0; bind()
            scope.launch {
                runCatching { Libre2Exchange.schedule(context, exchange.settings().libreView) }
                    .onFailure { logger.error("Libre2 export scheduling failed") }
            }
        }
    }

    internal suspend fun stop() {
        withContext(Dispatchers.Main.immediate) {
            enabled = false
            generation++
            handler.removeCallbacks(rebind)
            val old = connection
            connection = null
            old?.send(Libre2Ipc.STOP)
            old?.unbind()
            mutableStatus.value = Libre2Status()
        }
        // Plugin switching waits for an already-started DB transaction. Queued writes fail the epoch check.
        writes.withLock { }
    }

    private fun bind() {
        if (!enabled || connection != null) return
        val session = DecoderConnection(++generation)
        connection = session
        mutableStatus.value = Libre2Status(Libre2StatusCode.INITIALIZING)
        try {
            session.bound = context.bindService(Intent(context, Libre2SensorService::class.java), session, Context.BIND_AUTO_CREATE)
            if (!session.bound) disconnected(session)
        } catch (error: Exception) { disconnected(session) }
    }

    private fun disconnected(session: DecoderConnection) {
        if (connection !== session) return
        connection = null
        generation++
        session.unbind()
        mutableStatus.value = Libre2Status(Libre2StatusCode.ERROR, detail = "Libre2 decoder process disconnected")
        handler.removeCallbacks(rebind)
        // A native failure must not create an endless process-crash loop.
        if (enabled && crashes++ < 3) handler.postDelayed(rebind, 5_000L * crashes)
    }

    internal fun reconnect() = handler.post {
        if (!enabled) return@post
        crashes = 0
        if (connection == null) { handler.removeCallbacks(rebind); bind() }
        else connection?.send(Libre2Ipc.RECONNECT)
    }

    internal fun setupIntent(): Intent? = connection?.takeIf { enabled && status.value.nativeReady }?.let { session ->
        session.remote?.let { remote ->
            Intent(context, Libre2SetupActivity::class.java).putExtra("decoder", remote)
                .putExtra("session", session.sessionId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun accept(data: Bundle, session: DecoderConnection) {
        val reading = data.libre2ReadingOrNull() ?: return
        if (!Libre2ReadingPolicy.accepts(reading.timestamp, reading.mgdl, data.getInt("quality", -1), System.currentTimeMillis())) return
        if (!session.inFlight.add(reading.token)) return
        scope.launch {
            writes.withLock {
                try {
                    if (!enabled || session.epoch != generation) return@withLock
                    val sensorInsertion = if (preferences.get(BooleanKey.BgSourceCreateSensorChange)) {
                        val previous = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)?.timestamp
                        Libre2ReadingPolicy.sensorInsertionTime(reading.sensorStart, previous, System.currentTimeMillis())
                    } else null
                    persistenceLayer.insertCgmSourceData(Sources.BG, listOf(GV(
                        timestamp = reading.timestamp, value = reading.mgdl.toDouble(), raw = null, noise = null,
                        trendArrow = Libre2ReadingPolicy.trend(reading.trend), sourceSensor = SourceSensor.LIBRE_2
                    )), emptyList(), sensorInsertion)
                    // Export failures must never block ACK/CGM delivery or cause a pump reconnect.
                    try { exchange.onReading(reading) }
                    catch (_: Exception) { logger.error("Libre2 optional export failed") }
                    withContext(Dispatchers.Main.immediate) {
                        if (enabled && connection === session) {
                            mutableReadings.value = (listOf(reading) + mutableReadings.value)
                                .distinctBy { it.identity }.take(10)
                            // Confirm only after the transaction succeeds; retries keep the original timestamp.
                            session.send(Libre2Ipc.ACK_READING, Bundle().apply { putString("token", reading.token) })
                            rxBus.send(EventRefreshOverview("Libre2"))
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    logger.error("Libre2 glucose persistence failed", error)
                    withContext(Dispatchers.Main.immediate) {
                        session.inFlight.remove(reading.token)
                        if (enabled && connection === session)
                            mutableStatus.value = mutableStatus.value.copy(code = Libre2StatusCode.ERROR, detail = "Libre2 glucose persistence failed")
                    }
                } finally {
                    withContext(Dispatchers.Main.immediate) { session.inFlight.remove(reading.token) }
                }
            }
        }
    }
}

internal object Libre2ReadingPolicy {
    // Same five-minute tolerance and older-event suppression as xDrip's sensor-change registration.
    fun sensorInsertionTime(start: Long, previous: Long?, now: Long): Long? =
        start.takeIf { it > 0 && it <= now && now - it <= 30L * 24 * 60 * 60 * 1000 &&
            (previous == null || it > previous && it - previous > 300_000) }

    fun accepts(timestamp: Long, mgdl: Int, quality: Int, now: Long) =
        quality == 0 && mgdl in 1..1000 && timestamp > 0 &&
            timestamp <= now + 60_000 && timestamp >= now - 86_400_000
    fun trend(value: Int) = when (value) {
        1 -> TrendArrow.DOUBLE_DOWN
        2 -> TrendArrow.SINGLE_DOWN
        3 -> TrendArrow.FLAT
        4 -> TrendArrow.SINGLE_UP
        5 -> TrendArrow.DOUBLE_UP
        else -> TrendArrow.NONE
    }
}
