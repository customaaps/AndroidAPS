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
    private lateinit var delivery: Libre2DeliveryStore
    private var parentSession: String? = null
    @Volatile private var bindingGeneration = 0
    private var parent: Messenger? = null
    private var pairing: Libre2Pairing? = null
    private var bluetooth: Libre2Bluetooth? = null
    private var streaming = false
    private var nativeReady = false
    private var retry = 0
    private var status = Libre2StatusCode.STOPPED
    private var lastReading = 0L
    private val reconnect = Runnable { connect() }
    private val deliveryRetry = Runnable { deliverPending() }

    override fun onCreate() {
        super.onCreate()
        check(Application.getProcessName() == packageName + Libre2Ipc.PROCESS_SUFFIX) { "Libre2 decoder requires its dedicated process" }
        store = Libre2PairingStore(this)
        thread = HandlerThread("Libre2Decoder").also { it.start() }
        worker = object : Handler(thread.looper) {
            override fun handleMessage(message: Message) {
                if (message.sendingUid != android.os.Process.myUid()) return
                if (message.what != Libre2Ipc.REGISTER && message.data.getString("session") != parentSession) {
                    if (message.what == Libre2Ipc.SCAN)
                        send(message.replyTo, Libre2Ipc.SCAN_DONE, Bundle().apply { putString("error", "Libre2 source session changed") })
                    return
                }
                try { when (message.what) {
                    Libre2Ipc.REGISTER -> {
                        parent = message.replyTo ?: return
                        parentSession = message.data.getString("session") ?: return
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
                    Libre2Ipc.ACK_READING -> if (active.get() && ::delivery.isInitialized) {
                        message.data.getString("token")?.let { token ->
                            delivery.acknowledge(token)?.let { reading ->
                                lastReading = maxOf(lastReading, reading.timestamp)
                                if (streaming && reading.timestamp >= System.currentTimeMillis() - 180_000 &&
                                    reading.sensorId == pairing?.uid?.joinToString("") { "%02X".format(it.toInt() and 255) })
                                    report(Libre2StatusCode.RECEIVING)
                            }
                        }
                        deliverPending()
                    }
                    Libre2Ipc.STOP -> stopSensor()
                } } catch (error: Exception) {
                    Log.e("Libre2", "Sensor IPC operation failed", error)
                    report(Libre2StatusCode.ERROR, error.message.orEmpty())
                    if (active.get()) worker.postDelayed(deliveryRetry, 5_000)
                }
            }
        }
        messenger = Messenger(worker)
    }

    override fun onBind(intent: Intent?): IBinder {
        bindingGeneration++
        return messenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        active.set(false)
        val abandonedBinding = bindingGeneration
        worker.post { if (bindingGeneration == abandonedBinding) stopSensor() }
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
            if (!::delivery.isInitialized) delivery = Libre2DeliveryStore(this)
            deliverPending()
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
        streaming = false
        worker.removeCallbacks(reconnect)
        try {
            report(Libre2StatusCode.CONNECTING)
            val (newPairing, decoded) = Libre2NfcReader.read(tag, message.data.getBoolean("activate"), pairing)
            if (!active.get()) return
            commitDecoded(newPairing, decoded)
            retry = 0
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
        streaming = false
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
                onConnected = { streaming = true; report(Libre2StatusCode.CONNECTED) },
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
        streaming = false
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
        commitDecoded(saved.copy(state = result.state), result)
        if (result.removed) {
            bluetooth?.close()
            bluetooth = null
            streaming = false
            report(Libre2StatusCode.ERROR, "Libre2 sensor has ended or was removed")
            return
        }
    }

    private fun commitDecoded(candidate: Libre2Pairing, result: Libre2DecodeResult) {
        if (!active.get()) return
        var updated = candidate
        val timestamp = result.state.algorithmEndSeconds * 1000
        val now = System.currentTimeMillis()
        if (result.canPublish && result.sampleId >= 0 && result.sampleId > candidate.lastSampleId &&
            timestamp > candidate.lastReadingMillis && Libre2ReadingPolicy.accepts(timestamp, result.glucoseMgdl, result.quality, now)) {
            val reading = Libre2Reading(candidate.uid.joinToString("") { "%02X".format(it.toInt() and 255) },
                result.sampleId, timestamp, result.state.sensorStartSeconds * 1000, result.glucoseMgdl, result.trend)
            // The outbox must reach disk BEFORE advancing the algorithm/deduplication checkpoint.
            val queued = delivery.add(reading, now)
            updated = candidate.copy(lastSampleId = queued.sampleId, lastReadingMillis = queued.timestamp)
            retry = 0
        }
        try {
            store.save(updated)
            pairing = updated
        } finally { deliverPending() }
    }

    private fun deliverPending() {
        worker.removeCallbacks(deliveryRetry)
        if (!active.get() || parent == null || !::delivery.isInitialized) return
        try {
            delivery.next(System.currentTimeMillis())?.let { reading ->
                send(parent, Libre2Ipc.READING, reading.toBundle())
                worker.postDelayed(deliveryRetry, 5_000)
            }
        } catch (error: Exception) {
            Log.e("Libre2", "Reading delivery failed", error)
            report(Libre2StatusCode.ERROR, error.message.orEmpty())
            worker.postDelayed(deliveryRetry, 5_000)
        }
    }

    private fun stopSensor() {
        active.set(false)
        worker.removeCallbacks(reconnect)
        worker.removeCallbacks(deliveryRetry)
        bluetooth?.close()
        bluetooth = null
        streaming = false
        report(Libre2StatusCode.STOPPED)
        parent = null
        parentSession = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        active.set(false)
        if (::worker.isInitialized) worker.post {
            bluetooth?.close()
            bluetooth = null
            streaming = false
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
