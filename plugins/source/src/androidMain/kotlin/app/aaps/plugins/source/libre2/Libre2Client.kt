package app.aaps.plugins.source.libre2

import android.content.*
import android.os.*
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
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
) {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Mutex()
    private val mutableStatus = MutableStateFlow(Libre2Status())
    internal val status = mutableStatus.asStateFlow()
    @Volatile private var enabled = false
    private var bound = false
    private var remote: Messenger? = null
    @Volatile private var generation = 0
    private var crashes = 0
    private val rebind = Runnable { if (enabled && !bound) bind() }

    private val receiver = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (!enabled || message.sendingUid != android.os.Process.myUid()) return
            when (message.what) {
                Libre2Ipc.STATUS -> {
                    val code = Libre2StatusCode.entries.getOrNull(message.data.getInt("code")) ?: return
                    mutableStatus.value = Libre2Status(code, message.data.getBoolean("nativeReady"),
                        message.data.getString("detail").orEmpty(), message.data.getLong("lastReading"))
                    if (code == Libre2StatusCode.RECEIVING) crashes = 0
                }
                Libre2Ipc.READING -> accept(message.data)
            }
        }
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (!enabled) return
            remote = Messenger(service)
            send(Libre2Ipc.REGISTER)
        }
        override fun onServiceDisconnected(name: ComponentName) = disconnected()
        override fun onBindingDied(name: ComponentName) = disconnected()
        override fun onNullBinding(name: ComponentName) = disconnected()
    }

    internal fun start() = handler.post {
        if (!enabled) { enabled = true; generation++; crashes = 0; bind() }
    }

    internal fun stop() = handler.post {
        enabled = false
        generation++
        handler.removeCallbacks(rebind)
        send(Libre2Ipc.STOP)
        unbind()
        mutableStatus.value = Libre2Status()
    }

    private fun bind() {
        if (!enabled || bound) return
        mutableStatus.value = Libre2Status(Libre2StatusCode.INITIALIZING)
        try {
            bound = context.bindService(Intent(context, Libre2SensorService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!bound) disconnected()
        } catch (error: Exception) {
            disconnected()
        }
    }

    private fun unbind() {
        remote = null
        if (bound) { bound = false; runCatching { context.unbindService(connection) } }
    }

    private fun disconnected() {
        unbind()
        mutableStatus.value = Libre2Status(Libre2StatusCode.ERROR, detail = "Libre2 decoder process disconnected")
        // A native failure must not create an endless process-crash loop.
        if (enabled && crashes++ < 3) handler.postDelayed(rebind, 5_000L * crashes)
    }

    private fun send(what: Int) {
        try { remote?.send(Message.obtain(null, what).apply { replyTo = receiver }) }
        catch (error: RemoteException) { disconnected() }
    }

    internal fun reconnect() = handler.post {
        if (!enabled) return@post
        crashes = 0
        if (remote == null) { handler.removeCallbacks(rebind); bind() }
        else send(Libre2Ipc.RECONNECT)
    }

    internal fun setupIntent(): Intent? = remote?.takeIf { enabled && status.value.nativeReady }?.let {
        Intent(context, Libre2SetupActivity::class.java).putExtra("decoder", it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun accept(data: Bundle) {
        val timestamp = data.getLong("timestamp")
        val mgdl = data.getInt("mgdl")
        if (!Libre2ReadingPolicy.accepts(timestamp, mgdl, data.getInt("quality", -1), System.currentTimeMillis())) return
        val epoch = generation
        val sensorStart = data.getLong("sensorStart").takeIf { it in 1..timestamp }
        val trend = Libre2ReadingPolicy.trend(data.getInt("trend"))
        scope.launch {
            writes.withLock {
                if (!enabled || epoch != generation) return@withLock
                try {
                    persistenceLayer.insertCgmSourceData(Sources.BG, listOf(GV(
                        timestamp = timestamp, value = mgdl.toDouble(), raw = null, noise = null,
                        trendArrow = trend, sourceSensor = SourceSensor.LIBRE_2
                    )), emptyList(), sensorStart)
                    if (enabled && epoch == generation) rxBus.send(EventRefreshOverview("Libre2"))
                } catch (error: Exception) {
                    logger.error("Libre2 glucose persistence failed", error)
                }
            }
        }
    }
}

internal object Libre2ReadingPolicy {
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
