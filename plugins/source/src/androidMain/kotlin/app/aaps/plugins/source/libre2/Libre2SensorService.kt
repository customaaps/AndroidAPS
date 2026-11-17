package app.aaps.plugins.source.libre2

import android.Manifest
import android.app.*
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.nfc.Tag
import android.os.*
import android.util.Log
import app.aaps.plugins.source.R
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

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
    private var preferAutoConnect = false
    private var nativeReady = false
    private var foregroundStarted = false
    private var sensorEnded = false
    private var options = Libre2ConnectionOptions()
    private var diagnostics = Libre2Diagnostics()
    private lateinit var wakeProtection: Libre2WakeProtection
    private var wakeReceiverRegistered = false
    private var statusDetail = ""
    private var reconnectReason = ""
    private val hostWakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!active.get()) return
            updateProtection()
            bluetooth?.checkSilence()
            sendStatus()
        }
    }
    private val reconnectBackoff = Libre2ReconnectBackoff()
    private var status = Libre2StatusCode.STOPPED
    private var lastReading = 0L
    private val reconnect = Runnable {
        if (active.get() && options.autoRecovery && !sensorEnded) {
            recordReconnect(reconnectReason)
            connect()
        }
    }
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
                        options = Libre2ConnectionOptions.fromBundle(message.data)
                        initialize()
                    }
                    Libre2Ipc.SCAN -> if (active.get()) scan(message) else
                        send(message.replyTo, Libre2Ipc.SCAN_DONE, Bundle().apply { putString("error", "Libre2 source is stopped") })
                    Libre2Ipc.CONNECTION_OPTIONS -> if (active.get()) applyOptions(Libre2ConnectionOptions.fromBundle(message.data))
                    Libre2Ipc.RECONNECT -> if (active.get()) {
                        preferAutoConnect = false
                        recordReconnect("manual")
                        reconnectBackoff.reset()
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
        wakeProtection = Libre2WakeProtection(this, worker) {
            Log.w("Libre2", "CPU protection could not be enabled")
            report(status, "Libre2 CPU protection could not be enabled")
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_TIME_TICK)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(hostWakeReceiver, filter, null, worker, RECEIVER_NOT_EXPORTED)
            else registerReceiver(hostWakeReceiver, filter, null, worker)
            wakeReceiverRegistered = true
        } catch (_: Exception) { Log.w("Libre2", "Host wake diagnostics receiver could not be registered") }
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
        statusDetail = detail
        sendStatus()
    }

    private fun sendStatus() {
        send(parent, Libre2Ipc.STATUS, Bundle().apply {
            putInt("code", status.ordinal)
            putBoolean("nativeReady", nativeReady)
            putString("detail", statusDetail)
            putLong("lastReading", lastReading)
            diagnostics.copy(wakeProtectionHeld = wakeProtection.isHeld()).writeTo(this)
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
            updateProtection()
            if (!active.get()) return
            if (pairing == null) report(Libre2StatusCode.READY) else connect()
        } catch (error: Throwable) {
            wakeProtection.close()
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
            val (newPairing, decoded) = Libre2NfcReader.read(tag, message.data.getBoolean("activate"), pairing,
                repairPairing = message.data.getBoolean("repairPairing"))
            if (!active.get()) return
            sensorEnded = false
            preferAutoConnect = false
            recordDecode(decoded)
            commitDecoded(newPairing, decoded)
            reconnectBackoff.reset()
            send(receiver, Libre2Ipc.SCAN_DONE)
            connect()
        } catch (error: Throwable) {
            Log.e("Libre2", "NFC operation failed", error)
            report(Libre2StatusCode.ERROR, error.message.orEmpty())
            send(receiver, Libre2Ipc.SCAN_DONE, Bundle().apply { putString("error", error.message.orEmpty()) })
            // A failed scan must not leave the previously paired sensor disconnected.
            if (active.get() && pairing != null) {
                if (options.autoRecovery) scheduleReconnect("nfc_scan_failed", 5_000) else connect()
            }
        }
    }

    private fun permissionGranted() =
        Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun applyOptions(updated: Libre2ConnectionOptions) {
        val recoveryEnabled = !options.autoRecovery && updated.autoRecovery
        options = updated
        worker.removeCallbacks(reconnect)
        bluetooth?.setAutoRecovery(options.autoRecovery)
        if (!options.autoRecovery && bluetooth?.isWaiting == true) {
            bluetooth?.close()
            bluetooth = null
            streaming = false
            report(Libre2StatusCode.READY)
        }
        updateProtection()
        sendStatus()
        if (options.autoRecovery && bluetooth == null && pairing != null && !sensorEnded) {
            if (recoveryEnabled) reconnectBackoff.reset()
            scheduleReconnect("settings", reconnectBackoff.nextDelayMillis())
        }
    }

    private fun updateProtection() {
        val adapterEnabled = runCatching {
            permissionGranted() && getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        }.getOrDefault(false)
        wakeProtection.setEnabled(options.keepAwake && active.get() && foregroundStarted && nativeReady && pairing != null &&
            !sensorEnded && adapterEnabled && (bluetooth != null || options.autoRecovery))
    }

    private fun recordReconnect(reason: String) {
        diagnostics = diagnostics.copy(reconnects = diagnostics.reconnects + 1,
            lastReconnect = System.currentTimeMillis(), reconnectReason = reason)
        Log.i("Libre2", "Sensor reconnect #${diagnostics.reconnects}: $reason")
        sendStatus()
    }

    private fun scheduleReconnect(reason: String, delay: Long) {
        worker.removeCallbacks(reconnect)
        if (!active.get() || !options.autoRecovery || sensorEnded) return
        reconnectReason = reason
        worker.postDelayed(reconnect, delay)
    }

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
        foregroundStarted = true
    }

    private fun connect() {
        if (!active.get() || !nativeReady || sensorEnded) return
        val saved = pairing ?: run { report(Libre2StatusCode.READY); return }
        bluetooth?.close()
        bluetooth = null
        streaming = false
        if (!permissionGranted()) { updateProtection(); report(Libre2StatusCode.PERMISSION); return }
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
                autoConnect = options.autoRecovery && preferAutoConnect,
                onStage = { stage, gattStatus ->
                    diagnostics = diagnostics.copy(connectionStage = stage,
                        lastGattStatus = gattStatus ?: diagnostics.lastGattStatus,
                        lastGattEvent = if (gattStatus != null) System.currentTimeMillis() else diagnostics.lastGattEvent)
                    sendStatus()
                },
                onWaiting = { streaming = false; report(Libre2StatusCode.CONNECTING) },
                onConnected = { streaming = true; report(Libre2StatusCode.CONNECTED) },
                onPacket = { packet -> decode(packet) },
                onFailure = { error -> failedConnection(error) },
                onFragment = { arrivedAt ->
                    diagnostics = diagnostics.copy(lastFragment = arrivedAt, fragments = diagnostics.fragments + 1)
                    sendStatus()
                },
                onCompletePacket = { arrivedAt ->
                    diagnostics = diagnostics.copy(lastPacket = arrivedAt, packets = diagnostics.packets + 1)
                    sendStatus()
                },
            )
            bluetooth = connection
            connection.setAutoRecovery(options.autoRecovery)
            updateProtection()
            connection.connect()
        } catch (error: Exception) { failedConnection(error) }
    }

    private fun failedConnection(error: Exception) {
        bluetooth?.close()
        bluetooth = null
        streaming = false
        // Alternate back to an active attempt if Android auto-connect made no progress.
        preferAutoConnect = error !is Libre2Bluetooth.AutoConnectTimeout
        Log.w("Libre2", "Bluetooth connection failed", error)
        updateProtection()
        if (!active.get()) return
        if (!permissionGranted()) { report(Libre2StatusCode.PERMISSION); return }
        report(Libre2StatusCode.ERROR, error.message.orEmpty())
        scheduleReconnect(error.message ?: error.javaClass.simpleName, reconnectBackoff.nextDelayMillis())
    }

    private fun decode(packet: ByteArray) {
        if (!active.get()) return
        val saved = pairing ?: return
        val result = Libre2DecodeResult.fromNative(Libre2NativeDecoder.decodeBluetooth(
            saved.uid, saved.patchInfo, packet, System.currentTimeMillis() / 1000,
            saved.state.metadata(), saved.state.arrays
        ))
        recordDecode(result)
        Log.d("Libre2", "Decoded BLE sample: status=${result.status}, quality=${result.quality}, sampleId=${result.sampleId}, previousId=${saved.lastSampleId}, publishable=${result.canPublish}")
        if (result.status != 0) {
            report(Libre2StatusCode.ERROR, "Libre2 decoder status: ${result.status}")
            return
        }
        if (!active.get()) return
        commitDecoded(saved.copy(state = result.state), result)
        if (result.removed) {
            sensorEnded = true
            bluetooth?.close()
            bluetooth = null
            streaming = false
            updateProtection()
            worker.removeCallbacks(reconnect)
            report(Libre2StatusCode.ERROR, "Libre2 sensor has ended or was removed")
            return
        }
    }

    private fun recordDecode(result: Libre2DecodeResult) {
        diagnostics = diagnostics.copy(
            lastDecoded = if (result.canPublish) System.currentTimeMillis() else diagnostics.lastDecoded,
            decoded = diagnostics.decoded + if (result.canPublish) 1 else 0,
            decoderStatus = result.status, quality = result.quality, sampleId = result.sampleId)
        sendStatus()
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
            reconnectBackoff.reset()
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
        wakeProtection.close()
        worker.removeCallbacks(reconnect)
        worker.removeCallbacks(deliveryRetry)
        bluetooth?.close()
        bluetooth = null
        streaming = false
        report(Libre2StatusCode.STOPPED)
        parent = null
        parentSession = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    override fun onDestroy() {
        active.set(false)
        if (wakeReceiverRegistered) {
            wakeReceiverRegistered = false
            runCatching { unregisterReceiver(hostWakeReceiver) }
        }
        if (::worker.isInitialized) worker.post {
            if (::wakeProtection.isInitialized) wakeProtection.close()
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
