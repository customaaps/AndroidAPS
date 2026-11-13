package app.aaps.plugins.source.libre2

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.nfc.Tag
import android.os.*
import android.util.Log
import app.aaps.plugins.source.R
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/** No Metro/Room/pump initialization in this process. APS owns all glucose persistence. */
class Libre2SensorService : Service() {
    private val active = AtomicBoolean(false)
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var messenger: Messenger
    private lateinit var store: Libre2PairingStore
    private var parent: Messenger? = null
    private var pairing: Libre2Pairing? = null
    private var bluetooth: Libre2Bluetooth? = null
    private var nativeReady = false
    private var retry = 0
    private var status = Libre2StatusCode.STOPPED
    private var lastReading = 0L
    private val reconnect = Runnable { connect() }

    override fun onCreate() {
        super.onCreate()
        check(Application.getProcessName() == packageName + Libre2Ipc.PROCESS_SUFFIX) { "Libre2 decoder requires its dedicated process" }
        store = Libre2PairingStore(this)
        thread = HandlerThread("Libre2Decoder").also { it.start() }
        worker = object : Handler(thread.looper) {
            override fun handleMessage(message: Message) {
                if (message.sendingUid != android.os.Process.myUid()) return
                when (message.what) {
                    Libre2Ipc.REGISTER -> {
                        parent = message.replyTo ?: return
                        active.set(true)
                        initialize()
                    }
                    Libre2Ipc.SCAN -> if (active.get()) scan(message) else
                        send(message.replyTo, Libre2Ipc.SCAN_DONE, Bundle().apply { putString("error", "Libre2 source is stopped") })
                    Libre2Ipc.RECONNECT -> if (active.get()) {
                        retry = 0
                        worker.removeCallbacks(reconnect)
                        if (nativeReady) connect() else initialize()
                    }
                    Libre2Ipc.STOP -> stopSensor()
                }
            }
        }
        messenger = Messenger(worker)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        active.set(false)
        worker.post { stopSensor() }
        return false
    }

    private fun send(target: Messenger?, what: Int, data: Bundle = Bundle()) {
        if (target == null) return
        runCatching { target.send(Message.obtain(null, what).apply { this.data = data }) }
    }

    private fun report(code: Libre2StatusCode, detail: String = "") {
        status = code
        send(parent, Libre2Ipc.STATUS, Bundle().apply {
            putInt("code", code.ordinal)
            putBoolean("nativeReady", nativeReady)
            putString("detail", detail)
            putLong("lastReading", lastReading)
        })
    }

    private fun initialize() {
        report(Libre2StatusCode.INITIALIZING)
        try {
            check(Libre2NativeDecoder.probe()) { "Libre2 JNI adapter self-check failed" }
            Libre2NativeDecoder.load(File(applicationInfo.nativeLibraryDir, "libcalibrat2.so").absolutePath)
            nativeReady = true
            pairing = try { store.load() } catch (error: Exception) {
                Log.e("Libre2", "Saved sensor state is invalid; scan the sensor again", error)
                null
            }
            lastReading = pairing?.lastReadingMillis ?: 0
            if (!active.get()) return
            if (pairing == null) report(Libre2StatusCode.READY) else connect()
        } catch (error: Throwable) {
            Log.e("Libre2", "Decoder initialization failed", error)
            report(Libre2StatusCode.ERROR, error.message.orEmpty())
        }
    }

    @Suppress("DEPRECATION")
    private fun scan(message: Message) {
        val receiver = message.replyTo
        val tag = if (Build.VERSION.SDK_INT >= 33) message.data.getParcelable("tag", Tag::class.java)
                  else message.data.getParcelable<Tag>("tag")
        if (tag == null || !nativeReady) {
            send(receiver, Libre2Ipc.SCAN_DONE, Bundle().apply { putString("error", "Libre2 decoder is not ready") })
            return
        }
        bluetooth?.close()
        bluetooth = null
        worker.removeCallbacks(reconnect)
        try {
            report(Libre2StatusCode.CONNECTING)
            val (newPairing, decoded) = Libre2NfcReader.read(tag, message.data.getBoolean("activate"), pairing)
            if (!active.get()) return
            store.save(newPairing)
            pairing = newPairing
            lastReading = newPairing.lastReadingMillis
            retry = 0
            publish(decoded)
            send(receiver, Libre2Ipc.SCAN_DONE)
            connect()
        } catch (error: Throwable) {
            Log.e("Libre2", "NFC operation failed", error)
            report(Libre2StatusCode.ERROR, error.message.orEmpty())
            send(receiver, Libre2Ipc.SCAN_DONE, Bundle().apply { putString("error", error.message.orEmpty()) })
            // A failed scan must not leave the previously paired sensor disconnected.
            if (active.get() && pairing != null) worker.postDelayed(reconnect, 5_000)
        }
    }

    private fun permissionGranted() =
        Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun foreground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.source_libre2), NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.source_libre2))
            .setContentText(getString(R.string.libre2_stream_notification))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIFICATION, notification)
    }

    private fun connect() {
        if (!active.get() || !nativeReady) return
        val saved = pairing ?: run { report(Libre2StatusCode.READY); return }
        bluetooth?.close()
        bluetooth = null
        if (!permissionGranted()) { report(Libre2StatusCode.PERMISSION); return }
        try {
            foreground()
            report(Libre2StatusCode.CONNECTING)
            val connection = Libre2Bluetooth(
                this, worker, saved,
                reserveCounter = {
                    val current = requireNotNull(pairing)
                    check(current.uid.contentEquals(saved.uid))
                    check(current.unlockCounter < Int.MAX_VALUE)
                    val next = current.copy(unlockCounter = current.unlockCounter + 1)
                    store.save(next)
                    pairing = next
                    current.unlockCounter
                },
                onConnected = { report(Libre2StatusCode.CONNECTED) },
                onPacket = { packet -> decode(packet) },
                onFailure = { error -> failedConnection(error) },
            )
            bluetooth = connection
            connection.connect()
        } catch (error: Exception) { failedConnection(error) }
    }

    private fun failedConnection(error: Exception) {
        bluetooth?.close()
        bluetooth = null
        Log.w("Libre2", "Bluetooth connection failed", error)
        if (!active.get()) return
        if (!permissionGranted()) { report(Libre2StatusCode.PERMISSION); return }
        report(Libre2StatusCode.ERROR, error.message.orEmpty())
        worker.removeCallbacks(reconnect)
        val delay = min(60_000L, 5_000L shl min(retry++, 3))
        worker.postDelayed(reconnect, delay)
    }

    private fun decode(packet: ByteArray) {
        if (!active.get()) return
        val saved = pairing ?: return
        val result = Libre2DecodeResult.fromNative(Libre2NativeDecoder.decodeBluetooth(
            saved.uid, saved.patchInfo, packet, System.currentTimeMillis() / 1000,
            saved.state.metadata(), saved.state.arrays
        ))
        if (result.status != 0) {
            report(Libre2StatusCode.ERROR, "Libre2 decoder status: ${result.status}")
            return
        }
        if (!active.get()) return
        val updated = saved.copy(state = result.state)
        store.save(updated)
        pairing = updated
        if (result.removed) {
            bluetooth?.close()
            bluetooth = null
            report(Libre2StatusCode.ERROR, "Libre2 sensor has ended or was removed")
            return
        }
        publish(result)
    }

    private fun publish(result: Libre2DecodeResult) {
        if (!active.get() || !result.canPublish) return
        val timestamp = result.state.algorithmEndSeconds * 1000
        val now = System.currentTimeMillis()
        if (timestamp > now + 60_000 || timestamp < now - 24 * 60 * 60 * 1000 || timestamp <= lastReading) return
        val saved = pairing ?: return
        if (result.sampleId < 0 || result.sampleId <= saved.lastSampleId) return
        val published = saved.copy(lastSampleId = result.sampleId, lastReadingMillis = timestamp)
        store.save(published)
        pairing = published
        lastReading = timestamp
        retry = 0
        send(parent, Libre2Ipc.READING, Bundle().apply {
            putLong("timestamp", timestamp)
            putInt("mgdl", result.glucoseMgdl)
            putInt("quality", result.quality)
            putInt("trend", result.trend)
            putFloat("rate", result.rateMgdlPerMinute)
            putLong("sensorStart", result.state.sensorStartSeconds * 1000)
        })
        report(Libre2StatusCode.RECEIVING)
    }

    private fun stopSensor() {
        active.set(false)
        worker.removeCallbacks(reconnect)
        bluetooth?.close()
        bluetooth = null
        report(Libre2StatusCode.STOPPED)
        parent = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        active.set(false)
        if (::worker.isInitialized) worker.post {
            bluetooth?.close()
            bluetooth = null
            worker.removeCallbacksAndMessages(null)
            thread.quitSafely()
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "aaps-libre2"
        private const val NOTIFICATION = 7202
    }
}
