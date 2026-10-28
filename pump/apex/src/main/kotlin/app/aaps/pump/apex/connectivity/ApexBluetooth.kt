package app.aaps.pump.apex.connectivity

import app.aaps.pump.apex.interfaces.ApexBluetoothCallback
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.core.app.ActivityCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.interfaces.Preferences
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import app.aaps.core.utils.notifyAll
import app.aaps.core.utils.toHex
import app.aaps.core.utils.waitMillis
import app.aaps.pump.apex.ApexDriverLogic
import app.aaps.pump.apex.ApexDriverStatus
import app.aaps.pump.apex.R
import app.aaps.pump.apex.connectivity.commands.device.DeviceCommand
import app.aaps.pump.apex.connectivity.commands.pump.PumpCommand
import app.aaps.pump.apex.misc.ApexNotifications
import app.aaps.pump.apex.utils.keys.ApexBooleanKey
import app.aaps.pump.apex.utils.keys.ApexStringKey
import java.util.UUID
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn
import kotlin.math.min

@SingleIn(AppScope::class)
@Inject
class ApexBluetooth(
    val aapsLogger: AAPSLogger,
    val preferences: Preferences,
    val context: Context,
    val apexDriverStatus: ApexDriverStatus,
    val apexNotifications: ApexNotifications,
    val rh: ResourceHelper,
) {
    companion object {
        private val READ_SERVICE = ParcelUuid.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        private val WRITE_SERVICE = ParcelUuid.fromString("0000FFE5-0000-1000-8000-00805F9B34FB")

        private val READ_UUID = UUID.fromString("0000FFE4-0000-1000-8000-00805F9B34FB")
        private val WRITE_UUID = UUID.fromString("0000FFE9-0000-1000-8000-00805F9B34FB")
        private val CCC_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        /**
         * The pump's connection-password service - write FFC1, notify FFC2, one byte back saying
         * whether the six digits were accepted.
         *
         * Written to only behind `ApexBooleanKey.PumpHasPassword`, which is off by default: with the
         * toggle off nothing here is written and the driver behaves exactly as it did before this
         * service was named. [logGattInventoryOnce] still READS the service out of the
         * already-discovered list, whatever the toggle says, to record in the log whether this
         * firmware has FFC0 at all - that costs nothing and reaches no pump. With the toggle on the
         * password is written during the handshake, before the data channel: a locked pump has been
         * observed refusing the FFE4 CCC write until FFC1 is accepted, while this pump with `000000`
         * still opens the data channel after a Reject - which is why the outcome follows the data
         * channel, not the FFC2 byte.
         *
         * Only ever a VERIFY. The pump tells a verify from a password CHANGE by nothing but whether
         * the two halves of the twelve bytes are equal, and `ApexDriverLogic.passwordFrame` - the one
         * thing that builds a frame - cannot produce unequal halves. Setting or changing the pump's
         * password is out of scope and stays that way.
         */
        private val PASSWORD_SERVICE = UUID.fromString("0000FFC0-0000-1000-8000-00805F9B34FB")

        /** Password write, `props=0x08` on this pump: write WITH response. */
        private val PASSWORD_WRITE_UUID = UUID.fromString("0000FFC1-0000-1000-8000-00805F9B34FB")

        /** Password reply, `props=0x10`: notify. One byte back, 0..3. */
        private val PASSWORD_NOTIFY_UUID = UUID.fromString("0000FFC2-0000-1000-8000-00805F9B34FB")

        private const val WRITE_DELAY_MS = 1500

        /**
         * Minimum pause between closing a GATT client and registering a new one for the same device.
         * The stack needs a moment for that; without it connectGatt likes to answer status 133.
         */
        private const val GATT_SETTLE_MS = 100L

        /** After this many connections that never identified as a pump, drop the cached address. */
        private const val MAX_CONSECUTIVE_CONNECT_FAILURES = 3

        /**
         * The advertised name of the pump the user typed the serial for.
         *
         * Scan filters also match the generic FFE0/FFE5 UART services, so other nearby Apex pumps
         * (and nameless UART devices) reach the callback. Only this exact name is our pump.
         */
        @VisibleForTesting
        fun isTargetApexDevice(deviceName: String?, serial: String): Boolean {
            if (serial.isEmpty()) return false
            return deviceName == "APEX$serial"
        }

        /**
         * How long one connect attempt may stay in [Status.CONNECTING] before the driver drops it.
         *
         * The scan phase has no other limit. Android ends an LE scan without a word when the radio is
         * switched off, so neither `onScanFailed` nor `onScanResult` arrives and the status stays
         * CONNECTING for good; every way back in - `connect` itself, the retry loop in the service and
         * `ApexPumpPlugin.connect` - only acts on DISCONNECTED, so the pump stops dosing until the app
         * is restarted.
         *
         * 60 s is about ten times what a pump in range needs to answer a scan, and ending a scan too
         * early is cheap: the service starts a new attempt after at most 30 s of backoff. That is one
         * scan start per 90 s, far below the platform limit of five starts per 30 s.
         */
        private const val CONNECT_ATTEMPT_TIMEOUT_MS = 60_000L

        /**
         * Whether the attempt that started as [attemptId] is stuck and has to be dropped.
         *
         * False as soon as a newer attempt exists ([currentId] moved on), as soon as a GATT client is
         * open (the handshake has its own watchdog in `setupGatt`), or as soon as the link is up.
         */
        @VisibleForTesting
        fun connectAttemptStuck(attemptId: Int, currentId: Int, status: Status, gattOpen: Boolean): Boolean =
            attemptId == currentId && !gattOpen && status == Status.CONNECTING

        /**
         * Whether an `onDescriptorWrite` callback is the one that finishes the handshake.
         *
         * Until now the handshake wrote exactly one descriptor, so any callback meant "the read
         * subscription is up" and the connection could be promoted on the spot. Phase 1 adds a second
         * subscription (FFC2, the password reply), and without this the promotion - and with it
         * `onConnect()` and the whole initial exchange - would run a second time.
         *
         * Written as "anything except the password descriptor" rather than "only the read
         * characteristic", and the difference matters: the callback's descriptor is nullable, and on a
         * null this used to promote. Excluding one known UUID keeps every path that works today
         * behaving exactly as it did, and touches only the case this change introduces. A predicate
         * that promoted solely on [READ_UUID] would silently stop connecting a pump whose stack hands
         * back a null descriptor.
         *
         * A failed status is deliberately still promoted, exactly as before. That is not an
         * endorsement: a CCC write that fails leaves the link unable to receive anything, and the
         * right answer is probably to drop the connection. But changing it here would alter what
         * happens on a path that works today, for a reason unrelated to the password, so it stays as
         * it is and is logged instead.
         */
        @VisibleForTesting
        fun promotesConnection(descriptorCharacteristicUuid: UUID?, superseded: Boolean): Boolean =
            !superseded && descriptorCharacteristicUuid != PASSWORD_NOTIFY_UUID

        /**
         * While the BLE stack is blocked, repeat the log line — and, if the user swiped the
         * notification away, re-raise it — at most this often. The check runs on every connect
         * attempt and every command, so without this a single missing permission produces hundreds
         * of identical lines (250 in 8 minutes in a real first-setup log).
         */
        private const val BLOCKER_REPEAT_INTERVAL_MS = 5 * 60 * 1000L
    }

    private val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
    private var callback: ApexBluetoothCallback? = null

    private var bluetoothDevice: BluetoothDevice? = null

    /**
     * The one GATT client whose callbacks may drive this connection.
     *
     * Volatile because it is written by [setupGatt]/[closeGatt] on the command-executor and watchdog
     * threads and read by every GATT callback on a binder thread, where the identity check against it
     * decides whether a callback belongs to the live link or to a client that was already closed.
     */
    @Volatile private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null

    /**
     * True between asking the stack for a bigger MTU and getting the answer. Android also raises
     * `onMtuChanged` on its own for a connection event, and such a callback arrives before service
     * discovery has finished — see the handler for what that used to cost.
     */
    @Volatile private var mtuRequested = false

    /**
     * Released by `onDescriptorWrite` when the password subscription's descriptor write completes.
     *
     * `writeDescriptor` returns as soon as the request is queued, not when the pump has accepted it,
     * so without waiting here the password write would be issued as a second outstanding GATT
     * operation - which is exactly what the pacing exists to prevent, and what gets a write refused.
     */
    private val passwordSubscribeLock = Any()
    @Volatile private var passwordSubscribeStatus: Int? = null

    /**
     * Released by `onCharacteristicWrite` when the FFC1 password write completes on the stack.
     *
     * FFC1 is write-with-response, so its completion is a real callback (unlike a plain command write,
     * which this driver never waits on). The data channel must not be subscribed while this write is
     * still outstanding, so the gate waits on this latch before it probes FFE4.
     */
    private val passwordWriteLock = Any()
    @Volatile private var passwordWriteStatus: Int? = null

    /**
     * The connection-password gate for this client is running (a worker is doing the FFC0 exchange, or
     * about to). While it is true the helper writes are allowed during CONNECTING, and the FFE4
     * `onDescriptorWrite` follows the gate promotion table instead of `promotesConnection`. Cleared by
     * `closeGatt`, by the worker when it aborts before FFE4, and by the FFE4 callback.
     */
    @Volatile private var passwordGateActive = false

    /** True once the FFE4 CCC write has been accepted by the stack for this client. Cleared in `closeGatt`. */
    @Volatile private var ffe4CccIssued = false

    /** The gatt+id pair that set [passwordGateActive], so a stale worker never clears a newer client's flag. */
    @Volatile private var gateOwnerGatt: BluetoothGatt? = null
    @Volatile private var gateOwnerId: Int = -1

    /** Set once the GATT inventory has been written to the log; see [logGattInventoryOnce]. */
    @Volatile private var gattInventoryLogged = false

    /**
     * Write the pump's whole GATT inventory to the log, once per app process.
     *
     * The flag lives on this @SingleIn(AppScope::class) and is never reset, so a reconnect - or a driver restart,
     * which this class outlives - does not log it again. That is deliberate for a fact that cannot
     * change while the same pump is paired, but it means the line is in the log only if the export
     * covers the first connection after the app started.
     *
     * Read-only, and deliberately so. The driver has never enumerated services - it looks up the two
     * it needs and goes on - so nothing in any capture says which others the pump offers. The one
     * that matters is the connection-password service FFC0: whether it exists at all decides whether
     * a password feature has anything to attach to, and no amount of reading the maker's app can
     * answer that for a given firmware.
     *
     * If FFC0 is there, its characteristics are listed with their property bits too, because that is
     * the other unknown: whether a write to it expects a response. Both questions are answered by one
     * connection and cost one log line.
     */
    private fun logGattInventoryOnce(gatt: BluetoothGatt) {
        if (gattInventoryLogged) return
        gattInventoryLogged = true
        try {
            aapsLogger.info(LTag.PUMPBTCOMM, "GATT-INVENTORY: services = ${gatt.services.joinToString { it.uuid.toString() }}")
            val password = gatt.getService(PASSWORD_SERVICE)
            if (password == null) {
                aapsLogger.info(LTag.PUMPBTCOMM, "GATT-INVENTORY: no password service ($PASSWORD_SERVICE) on this pump")
            } else {
                // Descriptors, not only properties. A characteristic that can notify is not the same
                // as one that can be subscribed to: enabling notifications means writing the 0x2902
                // descriptor, and nothing says the pump declares one. Printing them here answers that
                // before any code depends on it.
                aapsLogger.info(
                    LTag.PUMPBTCOMM,
                    "GATT-INVENTORY: password service present, characteristics = " +
                        password.characteristics.joinToString {
                            "${it.uuid} props=0x${it.properties.toString(16)} " +
                                "descriptors=[${it.descriptors.joinToString { d -> d.uuid.toString() }}]"
                        }
                )
            }
        } catch (e: Exception) {
            // A diagnostic must never be the reason a connection fails.
            aapsLogger.error(LTag.PUMPBTCOMM, "GATT-INVENTORY: could not list services", e)
        }
    }

    private var mtu: Int = 512

    private val readMutex = Any()
    private var lastCommand: PumpCommand? = null

    /** Volatile: written from binder/watchdog/command threads and read by all of them, notably by the
     * setup watchdog deciding whether the handshake is still in progress. */
    @Volatile private var _status: Status = Status.DISCONNECTED

    val status: Status
        get() = _status

    private var prevCommandMs = SystemClock.uptimeMillis()
    private val connectionLock = Any()
    @Volatile private var connectionId: Int = 0
    // True only while send() is pacing+writing a command, so incoming data (tickDelay) doesn't reset
    // the inter-write timer mid-command. Replaces a kotlinx Mutex.isLocked check that was always false
    // (the Mutex was only ever used as a synchronized() monitor).
    @Volatile private var commandInFlight = false

    @Volatile private var consecutiveConnectFailures = 0

    /** [SystemClock.elapsedRealtime] of the last [closeGatt], for [GATT_SETTLE_MS]. */
    @Volatile private var lastGattCloseMs = 0L

    /** Overridden by tests only, so the watchdog can be seen without waiting a minute. */
    @VisibleForTesting internal var connectTimeoutMs = CONNECT_ATTEMPT_TIMEOUT_MS

    /** Called once the pump has answered as a pump: the cached address is good. */
    fun markConnectSuccess() {
        consecutiveConnectFailures = 0
    }

    /**
     * Called when a GATT connection was established but the device never identified itself as a pump.
     *
     * [ApexStringKey.BluetoothAddress] short-circuits scanning in [connect], so a stale address (pump
     * replaced, settings imported from another phone, a foreign device cached by an earlier scan)
     * would otherwise be retried forever and the driver would never scan again. Dropping it after
     * three consecutive failures forces a fresh scan instead of a permanent silent disconnect.
     */
    fun markConnectFailed() {
        consecutiveConnectFailures++
        aapsLogger.warn(LTag.PUMPBTCOMM, "onConnect failed (consecutive: $consecutiveConnectFailures)")
        if (consecutiveConnectFailures >= MAX_CONSECUTIVE_CONNECT_FAILURES) {
            consecutiveConnectFailures = 0
            aapsLogger.error(LTag.PUMPBTCOMM, "$MAX_CONSECUTIVE_CONNECT_FAILURES consecutive onConnect failures - clearing BT address to force a fresh scan")
            preferences.put(ApexStringKey.BluetoothAddress, "")
        }
    }

    fun setCallback(callback: ApexBluetoothCallback) {
        this.callback = callback
    }

    /**
     * Release [callback] if it is still the given one, and end any blocker episode with it.
     *
     * Called from `ApexService.onDestroy`, so this `@SingleIn(AppScope::class)` does not retain a destroyed service
     * (leak). It is also the last moment the blocker card can be taken down: [checkBT] runs only from
     * [connect] and [send], both of which come from the service, so a driver stopped while blocked
     * used to leave an IMPORTANT card standing for the rest of the app's life - over a pump the user
     * has just switched away from.
     *
     * The identity test matters: an owner that was already replaced must not take down the card of
     * the live service.
     */
    fun clearCallback(callback: ApexBluetoothCallback) {
        if (this.callback !== callback) return
        this.callback = null
        clearBlocker("Driver stopped - taking the Bluetooth blocker card down")
    }

    /**
     * Whether [gatt]+[btConnectionId] is still the live client. The gate worker re-checks this before
     * every Bluetooth call so a stale worker, woken by `closeGatt`, never touches a newer client.
     */
    fun isCurrent(gatt: BluetoothGatt?, btConnectionId: Int): Boolean =
        gatt != null && gatt === bluetoothGatt && btConnectionId == connectionId

    /** [connectionId] for the attempt now in flight, captured by `onMtuChanged` to own the gate. */
    fun gateConnectionId(): Int = connectionId

    /**
     * Clear the gate flag, but only if [expectedGatt]+[expectedId] still owns it and FFE4 was not
     * issued. The worker's cleanup path: a stale worker never clears a newer client's flag, and a
     * worker that already issued FFE4 leaves the flag for that write's callback to clear.
     */
    fun clearPasswordGateIfOwner(expectedGatt: BluetoothGatt, expectedId: Int) {
        if (gateOwnerGatt === expectedGatt && gateOwnerId == expectedId && !ffe4CccIssued) {
            passwordGateActive = false
            gateOwnerGatt = null
            gateOwnerId = -1
        }
    }

    /**
     * Common guard for the three gate writes. They may run while the link is still CONNECTING (the gate
     * is on) or after it is CONNECTED, but never on a stale or dead client. Returns the live gatt, or
     * null with a log line. Callers already hold [connectionLock].
     */
    private fun gateGattReady(expectedGatt: BluetoothGatt, what: String): BluetoothGatt? {
        if (checkBT()) {
            aapsLogger.error(LTag.PUMPBTCOMM, "PASSWORD: cannot $what - BT is not ready")
            return null
        }
        if (status != Status.CONNECTED && !passwordGateActive) {
            aapsLogger.error(LTag.PUMPBTCOMM, "PASSWORD: cannot $what - pump is disconnected")
            return null
        }
        val gatt = bluetoothGatt
        if (gatt == null || gatt !== expectedGatt) {
            aapsLogger.error(LTag.PUMPBTCOMM, "PASSWORD: cannot $what - GATT is stale or null")
            return null
        }
        return gatt
    }

    /**
     * Subscribe to the password reply characteristic (FFC2), and say whether the request was issued.
     *
     * Mirrors [send]: it takes [connectionLock] so it cannot interleave with a command write, honours
     * and re-stamps the [WRITE_DELAY_MS] pacing, and raises [commandInFlight] around the call. The
     * pacing is this driver's write serialisation - a GATT operation issued outside it is simply a
     * second outstanding operation that Android will refuse, and the one it refuses may be a bolus.
     * ([onCharacteristicWrite] is overridden for FFC1 only, so it never touches this pacing.)
     *
     * The descriptor is checked rather than assumed. A characteristic that can notify is not the same
     * as one that declares a 0x2902 descriptor, and the driver has never asked this pump for FFC2's.
     * Dereferencing a null here would throw on the bare thread that runs the gate, which kills the
     * process - a trap this driver has already paid for once.
     *
     * Returns false, with a log line, for every reason not to write. It never throws.
     */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun subscribePasswordReply(expectedGatt: BluetoothGatt): Boolean = synchronized(connectionLock) {
        try {
            val gatt = gateGattReady(expectedGatt, "subscribe") ?: return false
            val notify = gatt.getService(PASSWORD_SERVICE)?.getCharacteristic(PASSWORD_NOTIFY_UUID) ?: run {
                aapsLogger.info(LTag.PUMPBTCOMM, "PASSWORD: this pump has no $PASSWORD_NOTIFY_UUID - nothing to subscribe to")
                return false
            }
            val ccc = notify.getDescriptor(CCC_UUID) ?: run {
                aapsLogger.error(LTag.PUMPBTCOMM, "PASSWORD: $PASSWORD_NOTIFY_UUID declares no $CCC_UUID descriptor - cannot subscribe")
                return false
            }

            synchronized(passwordSubscribeLock) { passwordSubscribeStatus = null }
            commandInFlight = true
            try {
                val delta = WRITE_DELAY_MS - SystemClock.uptimeMillis() + prevCommandMs
                if (delta > 0) SystemClock.sleep(delta)
                prevCommandMs = SystemClock.uptimeMillis()

                gatt.setCharacteristicNotification(notify, true)
                val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                } else {
                    ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(ccc)
                }
                if (!accepted) {
                    aapsLogger.error(LTag.PUMPBTCOMM, "PASSWORD: subscribe REFUSED by the BLE stack")
                    return false
                }
                return true
            } finally {
                commandInFlight = false
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMPBTCOMM, "PASSWORD: could not subscribe", e)
            return false
        }
    }

    /**
     * Wait for the FFC2 subscription's descriptor write to complete, and return the raw callback status:
     * the GATT status if the callback arrived, or null if it never did within [timeoutMs].
     *
     * The tri-state matters. A null (timeout) means the descriptor write is still outstanding, so the
     * data channel must not be probed on top of it; a non-null status means the operation is closed,
     * success or not. Collapsing the two to a Boolean loses exactly the distinction the gate needs.
     *
     * Holds neither `connectionLock` nor [passwordSubscribeLock] across the wait: the callback that
     * releases this takes [passwordSubscribeLock] on a binder thread and `closeGatt` takes
     * `connectionLock` on another, so holding either here would be a way to deadlock a disconnect.
     */
    fun awaitPasswordSubscription(timeoutMs: Long): Int? {
        synchronized(passwordSubscribeLock) {
            if (passwordSubscribeStatus == null) passwordSubscribeLock.waitMillis(timeoutMs)
            return passwordSubscribeStatus
        }
    }

    /**
     * Write one password frame to FFC1, and say whether the BLE stack accepted the queue request.
     *
     * [frame] comes from `ApexDriverLogic.passwordFrame`, which is the only thing that builds it and
     * cannot build one whose two halves differ. That is what keeps this method from ever being a
     * password CHANGE: the pump tells the two apart by nothing else.
     *
     * FFC1 is write-with-response, so its completion arrives as [onCharacteristicWrite]; the caller
     * awaits [awaitPasswordWrite] before it probes the data channel. The password itself is never
     * logged, here or anywhere.
     *
     * Same contract as [subscribePasswordReply] and [send]: [connectionLock], the pacing, never a throw.
     */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun sendPasswordFrame(expectedGatt: BluetoothGatt, frame: ByteArray): Boolean = synchronized(connectionLock) {
        try {
            val gatt = gateGattReady(expectedGatt, "send") ?: return false
            val write = gatt.getService(PASSWORD_SERVICE)?.getCharacteristic(PASSWORD_WRITE_UUID) ?: run {
                aapsLogger.info(LTag.PUMPBTCOMM, "PASSWORD: this pump has no $PASSWORD_WRITE_UUID - nothing to send to")
                return false
            }

            synchronized(passwordWriteLock) { passwordWriteStatus = null }
            commandInFlight = true
            try {
                val delta = WRITE_DELAY_MS - SystemClock.uptimeMillis() + prevCommandMs
                if (delta > 0) SystemClock.sleep(delta)
                prevCommandMs = SystemClock.uptimeMillis()

                // Length only - the frame is the password.
                aapsLogger.debug(LTag.PUMPBTCOMM, "PASSWORD: writing ${frame.size} bytes to $PASSWORD_WRITE_UUID")
                val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(write, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                } else {
                    write.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    write.setValue(frame)
                    gatt.writeCharacteristic(write)
                }
                if (!accepted) aapsLogger.error(LTag.PUMPBTCOMM, "PASSWORD: write REFUSED by the BLE stack, password not sent")
                return accepted
            } finally {
                commandInFlight = false
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMPBTCOMM, "PASSWORD: could not send", e)
            return false
        }
    }

    /**
     * Wait for the FFC1 write to complete, and return the raw callback status, or null on timeout.
     * Tri-state for the same reason as [awaitPasswordSubscription]: a timeout leaves the write
     * outstanding, and the data channel must not be probed on top of it.
     */
    fun awaitPasswordWrite(timeoutMs: Long): Int? {
        synchronized(passwordWriteLock) {
            if (passwordWriteStatus == null) passwordWriteLock.waitMillis(timeoutMs)
            return passwordWriteStatus
        }
    }

    /**
     * Subscribe to the data channel (FFE4). These are the two lines `onMtuChanged` runs today; the gate
     * defers them until after the FFC1 exchange, because the pump refuses this write until it has seen a
     * valid password. On acceptance [ffe4CccIssued] is set here, under [connectionLock] and only while
     * the client is still current, so the promotion callback can tell its own completion from a stray.
     *
     * Returns whether the stack accepted the descriptor write.
     */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun enableReadNotifications(expectedGatt: BluetoothGatt): Boolean = synchronized(connectionLock) {
        try {
            val gatt = gateGattReady(expectedGatt, "subscribe data channel") ?: return false
            val read = readCharacteristic ?: run {
                aapsLogger.error(LTag.PUMPBTCOMM, "PASSWORD: cannot subscribe data channel - read characteristic is null")
                return false
            }
            val ccc = read.getDescriptor(CCC_UUID) ?: run {
                aapsLogger.error(LTag.PUMPBTCOMM, "PASSWORD: data channel declares no $CCC_UUID descriptor")
                return false
            }
            commandInFlight = true
            try {
                val delta = WRITE_DELAY_MS - SystemClock.uptimeMillis() + prevCommandMs
                if (delta > 0) SystemClock.sleep(delta)
                prevCommandMs = SystemClock.uptimeMillis()

                gatt.setCharacteristicNotification(read, true)
                val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                } else {
                    ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(ccc)
                }
                // Set only on acceptance and only while current: a closeGatt in the gap would have
                // cleared it, and writing it again would let a stray callback promote a dead link.
                if (accepted && gatt === bluetoothGatt) ffe4CccIssued = true
                else if (!accepted) aapsLogger.error(LTag.PUMPBTCOMM, "PASSWORD: data-channel subscribe REFUSED by the BLE stack")
                return accepted
            } finally {
                commandInFlight = false
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMPBTCOMM, "PASSWORD: could not subscribe data channel", e)
            return false
        }
    }

    private fun tickDelay() {
        if (!commandInFlight) prevCommandMs = SystemClock.uptimeMillis()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun send(command: DeviceCommand): Unit = synchronized(connectionLock) {
        if (checkBT())  {
            aapsLogger.error(LTag.PUMPBTCOMM, "Tried to invoke command but BT is not ready")
            return
        }
        if (status != Status.CONNECTED) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Tried to invoke command but pump is disconnected")
            return
        }
        if (writeCharacteristic == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Tried to invoke command but the write characteristic is null")
            return
        }
        if (bluetoothGatt == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Tried to invoke command but GATT is null")
            return
        }

        commandInFlight = true
        val delta = WRITE_DELAY_MS - SystemClock.uptimeMillis() + prevCommandMs
        if (delta > 0) SystemClock.sleep(delta)
        prevCommandMs = SystemClock.uptimeMillis()

        val data = command.serialize()
        var start = 0
        while (start < data.size) {
            val end = min(start + mtu, data.size)
            val chunk = data.copyOfRange(start, end)

            aapsLogger.debug(LTag.PUMPBTCOMM, "DEVICE[$start] -> ${chunk.toHex()}")
            // Android silently refuses a write while another is outstanding or the GATT is busy; the
            // command then never reaches the pump and only surfaces as an unexplained timeout further
            // up. Surface the refusal itself so an exported log can tell "pump ignored us" apart from
            // "we never sent it".
            val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGatt!!.writeCharacteristic(
                    writeCharacteristic!!,
                    chunk,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                writeCharacteristic!!.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                writeCharacteristic!!.setValue(chunk)
                bluetoothGatt!!.writeCharacteristic(writeCharacteristic!!)
            }
            if (!accepted) aapsLogger.error(LTag.PUMPBTCOMM, "DEVICE[$start] -> write REFUSED by the BLE stack, command not sent")

            start = end
        }
        commandInFlight = false
    }

    @SuppressLint("MissingPermission")
    fun connect(): Unit = synchronized (connectionLock) {
        if (_status != Status.DISCONNECTED) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Already connecting! Ignoring repeated request")
            return
        }

        aapsLogger.debug(LTag.PUMPBTCOMM, "Connect")
        // Same rule as isConfigured(), not a bare isEmpty(): the scan filter demands the exact
        // name "APEX" + an 8-digit serial, so a malformed serial can never match a pump and a
        // scan for it would only loop through the connect watchdog forever.
        if (!ApexDriverLogic.isSerialConfigured(preferences.get(ApexStringKey.SerialNumber))) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Serial number is not configured - not scanning")
            return
        }
        if (checkBT()) return
        _status = Status.CONNECTING
        connectionId++
        armConnectWatchdog()
        if (preferences.get(ApexStringKey.BluetoothAddress).isNotEmpty()) return reconnect()

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "BLE scanner unavailable (Bluetooth disabled?)")
            _status = Status.DISCONNECTED
            return
        }
        aapsLogger.debug(LTag.PUMPBTCOMM, "Scan started")
        try {
            scanner.startScan(scanFilters(), scanSettings, scanCallback)
        } catch (e: Exception) {
            // The adapter can go off between the getter above and this call; the scanner then throws.
            // connect() runs on a raw thread, where an uncaught exception takes the whole app down.
            aapsLogger.error(LTag.PUMPBTCOMM, "Failed to start the scan", e)
            _status = Status.DISCONNECTED
        }
    }

    /**
     * Drop a connect attempt that never left [Status.CONNECTING].
     *
     * The watchdog in [setupGatt] only covers an attempt that already has a GATT client. This one is
     * armed by [connect] itself, so it also covers the scan phase - the phase Android can end without
     * a callback when the radio goes off - and a [reconnect] that threw before any client existed.
     *
     * Two guards keep it away from a healthy link, both in [connectAttemptStuck]: the connection id
     * must still be the one this attempt started with, and no GATT client may be open.
     * `bluetoothGatt == null` while CONNECTING is the same "still scanning" test [disconnect] uses; a
     * handshake that is already running belongs to the watchdog in [setupGatt].
     */
    private fun armConnectWatchdog() {
        // Read here, in the calling thread under connectionLock: inside the thread it could already be
        // the id of the NEXT attempt, and this watchdog would then judge an attempt that is not its own.
        val attemptId = connectionId
        Thread {
            SystemClock.sleep(connectTimeoutMs)
            val timedOut = synchronized(connectionLock) {
                if (!connectAttemptStuck(attemptId, connectionId, _status, bluetoothGatt != null)) false
                else {
                    aapsLogger.error(LTag.PUMPBTCOMM, "Connecting | Timed out while looking for the pump")
                    stopScan()
                    _status = Status.DISCONNECTED
                    apexDriverStatus.updateConnectionState(ApexDriverStatus.ConnectionState.Disconnected)
                    true
                }
            }
            // Outside the lock, like the disconnect path in the GATT callback: the service starts its
            // retry loop from here and that loop takes the same lock.
            if (timedOut) callback?.onDisconnect()
        }.start()
    }

    @SuppressLint("MissingPermission")
    fun disconnect() = synchronized(connectionLock) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "Disconnecting")
        apexDriverStatus.updateConnectionState(ApexDriverStatus.ConnectionState.Disconnecting)
        stopScan()
        // (gatt == null && CONNECTING) is the scanning phase, i.e. the fresh-pairing state. Bailing
        // out here left _status stuck at CONNECTING with the scan still running: connect() refuses to
        // start while not DISCONNECTED and nothing else resets it (the 25 s watchdog only arms once
        // connectGatt succeeded), so the driver stayed wedged until the app was restarted.
        if (bluetoothGatt != null) {
            closeGatt()
        } else {
            _status = Status.DISCONNECTED
        }
        apexDriverStatus.updateConnectionState(ApexDriverStatus.ConnectionState.Disconnected)
    }

    /**
     * A reason the driver must not touch the BLE stack, i.e. a stop condition the user alone can clear.
     *
     * Carries the user-visible text and the card id, so the notification, the snackbar and the log
     * line cannot drift apart, and doubles as the episode key in [reportedBlocker].
     */
    private enum class BleBlocker(@StringRes val textRes: Int, val notificationId: Int, val logMessage: String) {

        /** Nearby devices (`BLUETOOTH_CONNECT` + `BLUETOOTH_SCAN`, one runtime group) is not granted. */
        NO_PERMISSION(app.aaps.core.ui.R.string.need_connect_permission, ApexNotifications.PERMISSION_MISSING, "No Bluetooth permission!"),

        /** The radio is switched off. Nothing the driver does can bring it back. */
        RADIO_OFF(app.aaps.core.ui.R.string.ble_not_enabled, ApexNotifications.BLUETOOTH_OFF, "Bluetooth is turned off!"),
    }

    /**
     * Reason the BLE stack is unusable that the user has already been told about, or null while the
     * stack is fine.
     *
     * [checkBT] runs on every connect attempt and every command, so the blocked branch is hit
     * continuously while the driver retries — a real first-setup log has 250 hits in 8 minutes,
     * with nothing but a climbing "Connecting" counter shown to the person setting the pump up. This
     * marks the start of an episode: the snackbar fires exactly once on that transition, and the
     * whole state is cleared as soon as the check passes, or the driver stops - see [clearCallback].
     */
    @Volatile private var reportedBlocker: BleBlocker? = null

    /**
     * [SystemClock.elapsedRealtime] of the last blocker log line / notification re-post, for
     * [BLOCKER_REPEAT_INTERVAL_MS].
     */
    @Volatile private var lastBlockerReportMs = 0L

    /**
     * Tell the user why the pump will not connect, and keep that explanation reachable.
     *
     * Ported back from the original driver, which raised both an error toast and a notification here
     * ([Toast] is that toast). Nothing else watches the BLE stack on behalf of a pump driver,
     * so without this the failure is completely silent.
     *
     * Repetition is throttled to [BLOCKER_REPEAT_INTERVAL_MS] because this runs on every retry.
     * Whether the card still exists is not remembered here: the user can swipe it away (the system
     * notification's delete intent routes back into the store's `remove`), and a local "already
     * posted" flag would then leave a pump that never connects with no explanation at all. This
     * version has no read side to the notification store either, so `ApexNotifications.isOnScreen`
     * answers false and the card is simply posted again on each throttle tick. That costs nothing in
     * the common case: `NotificationStore.add` only refreshes a card that still stands, and returns
     * before it can raise the system notification or a sound a second time.
     */
    private fun onBleBlocked(blocker: BleBlocker) {
        // Nobody to tell once the driver is gone. The service sets the callback in onCreate before it
        // can connect and drops it in onDestroy, so a null one means a connect attempt from a thread
        // that has not noticed the teardown yet. Without this it could raise the card again right
        // after clearCallback took it down, and then nothing would ever take it back down.
        if (callback == null) return

        val previous = reportedBlocker
        val episodeStart = previous != blocker
        if (episodeStart) {
            // The blockers are mutually exclusive and live on different ids, so the card of the one
            // that is gone has to come down - otherwise "no permission" keeps standing while the real
            // reason is now a switched-off radio.
            if (previous != null) apexNotifications.dismiss(previous.notificationId)
            reportedBlocker = blocker
            Handler(Looper.getMainLooper()).post { Toast.makeText(context, rh.gs(blocker.textRes), Toast.LENGTH_LONG).show() }
        }

        val now = SystemClock.elapsedRealtime()
        if (!episodeStart && now - lastBlockerReportMs < BLOCKER_REPEAT_INTERVAL_MS) return
        lastBlockerReportMs = now
        aapsLogger.error(LTag.PUMPBTCOMM, blocker.logMessage)

        // On an episode start always post, so that switching from one blocker to the other updates the
        // text; otherwise post to put back a card the user may have swiped away. This version cannot
        // answer that, so the re-post always happens - silently, when the card still stands.
        if (episodeStart || !apexNotifications.isOnScreen(blocker.notificationId)) {
            // Severity is the id's own default - no override. That is still visible outside the app:
            // NotificationStore mirrors every action-less post to an Android notification while
            // BooleanKey.AlertUrgentAsAndroidNotification is set (default true), as PRIORITY_MAX with a
            // short vibration. URGENT would additionally mean a 4 s vibration and the default alarm
            // ringtone on STREAM_ALARM - an audible alarm, not merely a higher place in the list.
            apexNotifications.post(blocker.notificationId, rh.gs(blocker.textRes))
        }
    }

    /** The stack is usable again - take down the card raised by [onBleBlocked]. */
    private fun onBleReady() = clearBlocker("Bluetooth is usable again")

    /**
     * End the current blocker episode, if there is one, and take its card down.
     *
     * [logMessage] says why. A driver teardown and a radio that came back look identical in the log
     * afterwards, and they mean opposite things.
     *
     * Only the in-app card goes: `NotificationStore.remove` does not cancel the copy Android shows
     * in the shade, so that line stays until the user swipes it. That is a general gap of the
     * notification code, not of this driver.
     */
    private fun clearBlocker(logMessage: String) {
        val previous = reportedBlocker ?: return
        reportedBlocker = null
        lastBlockerReportMs = 0L
        aapsLogger.debug(LTag.PUMPBTCOMM, logMessage)
        apexNotifications.dismiss(previous.notificationId)
    }

    /**
     * Preconditions for touching the BLE stack at all. Returns true when the driver must not proceed.
     *
     * No location check any more. minSdk is 31 and the manifest asserts `neverForLocation` on
     * `BLUETOOTH_SCAN`, so neither a scan nor a reconnect to a known address needs
     * ACCESS_FINE/COARSE_LOCATION. AAPS only ever asks for location for the Automation location
     * trigger, so the old unconditional check refused to connect at all on a phone where that trigger
     * was never used - including the reconnect path, which does not scan.
     */
    @SuppressLint("MissingPermission")
    private fun checkBT(): Boolean {
        // One runtime group (Nearby devices), but both are asked for by name: CONNECT gates
        // connectGatt and every write, SCAN gates startScan in connect().
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
        ) {
            onBleBlocked(BleBlocker.NO_PERMISSION)
            return true
        }

        val adapter = bluetoothAdapter
        if (adapter == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "No Bluetooth adapter!")
            return true
        }

        // A radio that is off is a stop condition, not a slow link: connectGatt and startScan would
        // fail at once, and the reconnect loop would keep asking every 30 s for as long as it stays
        // off. The way back is the adapter-state receiver in ApexService, which retries the moment it
        // is on again - a stop condition without that receiver would sleep forever.
        if (!adapter.isEnabled) {
            onBleBlocked(BleBlocker.RADIO_OFF)
            return true
        }

        onBleReady()
        return false
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun reconnect() {
        aapsLogger.debug(LTag.PUMPBTCOMM, "Connecting | Setting up GATT")
        apexDriverStatus.updateConnectionState(ApexDriverStatus.ConnectionState.Connecting)
        bluetoothDevice = bluetoothAdapter!!.getRemoteDevice(preferences.get(ApexStringKey.BluetoothAddress))
        setupGatt()
    }

    @SuppressLint("MissingPermission")
    private fun setupGatt() {
        // Do not allow multiple GATTs
        if (bluetoothGatt != null) closeGatt()
        mtuRequested = false
        // Covers every re-open path, not just the one above: ApexService.disconnect(isReconnect=true)
        // closes and calls connect() straight after, and that fast reconnect used to get its pause
        // from the sleep inside closeGatt().
        val settle = GATT_SETTLE_MS - (SystemClock.elapsedRealtime() - lastGattCloseMs)
        if (settle > 0) SystemClock.sleep(settle)
        bluetoothGatt = bluetoothDevice!!.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

        if (bluetoothGatt == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Connecting | Failed to set up GATT")
            _status = Status.DISCONNECTED
            return
        }

        // An attempt is now in flight, so say so. closeGatt() above (taken when a previous client was still
        // open) drops the status to DISCONNECTED, and the reconnect loop polls every 100 ms - without this the
        // loop would keep issuing connect() while this attempt is still running, registering and dropping a GATT
        // client about ten times a second. Must stay AFTER the null check: a connectGatt that returned null has
        // to fall through as DISCONNECTED.
        _status = Status.CONNECTING

        Thread {
            val prevConnId = connectionId
            SystemClock.sleep(25000)
            if (prevConnId != connectionId) return@Thread

            if (status == Status.CONNECTING) {
                aapsLogger.error(LTag.PUMPBTCOMM, "Connecting | Timed out setting up GATT")
                synchronized (connectionLock) { closeGatt() }
            }
        }.start()
    }

    private fun onPumpData(notifyingGatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "PUMP <- ${value.toHex()}")
        tickDelay()
        when (characteristic.uuid) {
            // Its own branch and its own try/catch. This runs on a binder thread, and the try/catch
            // that wraps the password step in ApexService does not reach here - an exception thrown on
            // this thread has nothing above it either.
            //
            // Dropped from a superseded client: a late FFC2 from a closed gatt must not overwrite the
            // next gate's verdict, and the service keys nothing on a reply that is not the live one.
            PASSWORD_NOTIFY_UUID -> if (notifyingGatt != null && notifyingGatt !== bluetoothGatt) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "PASSWORD: ignoring a reply from a superseded GATT client")
            } else try {
                callback?.onPasswordReply(value)
            } catch (e: Exception) {
                aapsLogger.error(LTag.PUMPBTCOMM, "PASSWORD: failed to handle the reply", e)
            }

            READ_UUID -> synchronized(readMutex) {
                // Update command or create new one
                if (lastCommand?.isCompleteCommand() == false)
                    lastCommand!!.update(value)
                else if (value.size > PumpCommand.MIN_SIZE)
                    lastCommand = PumpCommand(value)
                else
                    aapsLogger.error(LTag.PUMPBTCOMM, "Got invalid command of length ${value.size}")

                while (lastCommand != null && lastCommand!!.isCompleteCommand()) {
                    if (!lastCommand!!.verify()) {
                        aapsLogger.error(LTag.PUMPBTCOMM, "[${lastCommand!!.id?.name}] Command checksum is invalid! Expected ${lastCommand!!.checksum.toHex()}")
                        return
                    }

                    callback?.onPumpCommand(lastCommand!!)
                    lastCommand = lastCommand!!.trailing
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        aapsLogger.debug(LTag.PUMPBTCOMM, "Scan stopped")
        // The scanner throws when the adapter goes off between the getter and the call. Every caller
        // runs on a raw thread, where an uncaught exception takes the whole app down.
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Failed to stop the scan", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        aapsLogger.debug(LTag.PUMPBTCOMM, "Closing GATT")

        // Null fields and invalidate the connection id BEFORE closing, so any in-flight GATT callback
        // or the connect-timeout thread for this connection bails out instead of touching stale state.
        val gatt = bluetoothGatt
        bluetoothGatt = null
        writeCharacteristic = null
        readCharacteristic = null
        mtuRequested = false
        connectionId++

        // The gate for this client is over. Clear its flags and wake anything waiting on the FFC2 or
        // FFC1 latch so a worker parked on a wait returns at once and then bails on !isCurrent, instead
        // of sleeping out its full timeout against a client that no longer exists.
        passwordGateActive = false
        ffe4CccIssued = false
        gateOwnerGatt = null
        gateOwnerId = -1
        synchronized(passwordSubscribeLock) { passwordSubscribeLock.notifyAll() }
        synchronized(passwordWriteLock) { passwordWriteLock.notifyAll() }

        gatt?.disconnect()
        gatt?.close()

        // No sleep here. closeGatt runs under connectionLock - the same lock send() holds - and on the
        // GATT callback thread, so a blanket 100 ms pause delayed the command path and a binder
        // callback on every plain teardown. Only re-opening needs a pause, so record when the client
        // went away and let setupGatt() wait if it is about to reconnect right now.
        lastGattCloseMs = SystemClock.elapsedRealtime()
        _status = Status.DISCONNECTED
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            when (newState) {
                BluetoothGatt.STATE_DISCONNECTING -> {
                    aapsLogger.debug(LTag.PUMPBTCOMM, "Disconnecting (status=$status)")
                }
                BluetoothGatt.STATE_DISCONNECTED   -> {
                    // Only a stale callback that arrives while a DIFFERENT client is already live may be
                    // dropped: that one would tear down a healthy link. When bluetoothGatt is null the
                    // teardown is ours (closeGatt nulls the field before disconnecting), and the service
                    // still has to learn about it - swallowing it here would stop the reconnect loop.
                    if (bluetoothGatt != null && gatt !== bluetoothGatt) {
                        aapsLogger.debug(LTag.PUMPBTCOMM, "Ignoring Disconnected (status=$status) from a superseded GATT client")
                        return
                    }
                    apexDriverStatus.updateConnectionState(ApexDriverStatus.ConnectionState.Disconnected)
                    _status = Status.DISCONNECTED
                    aapsLogger.debug(LTag.PUMPBTCOMM, "Disconnected (status=$status)")

                    synchronized(connectionLock) { closeGatt() }
                    Thread { callback?.onDisconnect() }.start()
                }
                BluetoothGatt.STATE_CONNECTING     -> {
                    aapsLogger.debug(LTag.PUMPBTCOMM, "Connecting (status=$status)")
                }
                BluetoothGatt.STATE_CONNECTED      -> {
                    // A late Connected from a client we already closed used to start a second handshake
                    // on the live one (discoverServices below runs against the field, not against gatt),
                    // which ran the whole setup twice in parallel - two onConnect(), the pump clock
                    // written twice, the bolus history read twice.
                    if (gatt !== bluetoothGatt) {
                        aapsLogger.debug(LTag.PUMPBTCOMM, "Ignoring Connected (status=$status) from a superseded GATT client")
                        return
                    }
                    aapsLogger.debug(LTag.PUMPBTCOMM, "Connecting | Connected, discovering services (status=$status)")
                    _status = Status.CONNECTING
                    bluetoothGatt?.discoverServices()
                }
            }
        }

        /**
         * True when this callback comes from a GATT client that has since been closed and replaced.
         *
         * Such a callback must not advance the handshake of the live connection, and must not run the
         * error branches either - those call [disconnect] and would drop a link that is perfectly fine.
         */
        private fun isSupersededGatt(gatt: BluetoothGatt?): Boolean =
            if (gatt !== bluetoothGatt) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "Ignoring callback from a superseded GATT client")
                true
            } else false

        @Suppress("DEPRECATION")
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            // Would otherwise install the characteristics of a dead client over the live connection.
            if (isSupersededGatt(gatt)) return
            // Android delivers this callback for a plain connection event too, not only in answer to
            // requestMtu. Such a callback arrives before service discovery, when getService() still
            // returns null, so the two lookups below emptied the characteristics of a perfectly healthy
            // link and the guard after them tore it down: seven register/close cycles inside eight
            // seconds were recorded on one connect, and the same close/open path is the driver's only
            // retry for a failed dosing command.
            if (!mtuRequested) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "Connecting | Ignoring an MTU callback we did not ask for")
                return
            }
            mtuRequested = false

            // Into locals first: a callback that turns out to be unusable must not leave the live
            // connection without its characteristics.
            val write = gatt?.getService(WRITE_SERVICE.uuid)?.getCharacteristic(WRITE_UUID)
            val read = gatt?.getService(READ_SERVICE.uuid)?.getCharacteristic(READ_UUID)

            if (status != BluetoothGatt.GATT_SUCCESS || write == null || read == null) {
                aapsLogger.error(LTag.PUMPBTCOMM, "Failed to update MTU")
                disconnect()
                return
            }
            writeCharacteristic = write
            readCharacteristic = read

            _status = Status.CONNECTING
            this@ApexBluetooth.mtu = mtu
            aapsLogger.debug(LTag.PUMPBTCOMM, "Connecting | Updated MTU=$mtu, requesting notification")

            // The pump refuses the data-channel subscription until it has seen a valid connection
            // password. With the toggle on and the password service present, the FFC0 exchange runs
            // first, on a worker; the data channel is subscribed only after it. Toggle off, or a pump
            // without FFC0: the two lines below, exactly as before the gate existed.
            val gateGatt = gatt
            val useGate = preferences.get(ApexBooleanKey.PumpHasPassword) &&
                gateGatt.getService(PASSWORD_SERVICE) != null
            if (useGate) {
                val gateId = connectionId
                if (passwordGateActive && gateOwnerGatt === gateGatt && gateOwnerId == gateId) {
                    aapsLogger.debug(LTag.PUMPBTCOMM, "PASSWORD: gate already running for this client")
                    return
                }
                // A stale flag from a client that closed without a clean clear: take it over.
                passwordGateActive = true
                ffe4CccIssued = false
                gateOwnerGatt = gateGatt
                gateOwnerId = gateId
                aapsLogger.debug(LTag.PUMPBTCOMM, "PASSWORD: starting the connection-password gate")
                Thread { callback?.onPasswordGate(gateGatt, gateId) }.start()
                return
            }

            enableReadNotificationsUnchecked(gateGatt)
        }

        /**
         * The two data-channel-subscribe lines as they were before the gate existed, issued straight
         * from `onMtuChanged` when the gate is not in play. Kept literal so the toggle-off connect path
         * stays byte-identical.
         */
        @Suppress("DEPRECATION")
        private fun enableReadNotificationsUnchecked(gatt: BluetoothGatt) {
            gatt.setCharacteristicNotification(readCharacteristic, true)
            val ccc = readCharacteristic!!.getDescriptor(CCC_UUID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(ccc)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (isSupersededGatt(gatt)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                aapsLogger.error(LTag.PUMPBTCOMM, "Failed to discover services")
                disconnect()
                return
            }

            if (gatt.getService(READ_SERVICE.uuid) == null || gatt.getService(WRITE_SERVICE.uuid) == null) {
                aapsLogger.error(LTag.PUMPBTCOMM, "R/W services were not found!")
                disconnect()
                return
            }

            logGattInventoryOnce(gatt)

            _status = Status.CONNECTING
            aapsLogger.debug(LTag.PUMPBTCOMM, "Connecting | Requested services, requesting MTU")
            mtuRequested = true
            gatt.requestMtu(512)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
            val uuid = descriptor?.characteristic?.uuid

            // The FFC2 password-reply CCC only ever arms its own latch, and only for the live client.
            // It never promotes: without the gate this is excluded by promotesConnection; with the gate
            // the table below is keyed on the data-channel write, not on this one.
            if (uuid == PASSWORD_NOTIFY_UUID) {
                if (gatt === bluetoothGatt) synchronized(passwordSubscribeLock) {
                    passwordSubscribeStatus = status
                    passwordSubscribeLock.notifyAll()
                }
                return
            }

            // Toggle off: byte-identical to the pre-gate handshake. FFE4 or a null descriptor promotes
            // regardless of status; a superseded client does not.
            if (!passwordGateActive) {
                if (!promotesConnection(uuid, isSupersededGatt(gatt))) {
                    aapsLogger.debug(LTag.PUMPBTCOMM, "Descriptor write on $uuid status=$status - not the handshake")
                    return
                }
                if (status != BluetoothGatt.GATT_SUCCESS)
                    aapsLogger.warn(LTag.PUMPBTCOMM, "Read notification descriptor write failed with status $status - promoting anyway, as before")
                promoteConnected(gatt, status)
                return
            }

            // Gate on: only the data-channel write (FFE4), or - because the stack may report its
            // completion with a null descriptor - a null callback after FFE4 was issued, may promote,
            // and only on success. A refused FFE4 locks out unless the pump already accepted the
            // password. Everything else (a stray, a late FFC2, a pre-FFE4 callback) is ignored.
            val kind = when {
                uuid == READ_UUID -> ApexDriverLogic.CallbackKind.Ffe4
                descriptor == null -> ApexDriverLogic.CallbackKind.NullDescriptor
                else               -> ApexDriverLogic.CallbackKind.Other
            }
            val action = ApexDriverLogic.passwordHandshakeAction(
                ffe4Issued = ffe4CccIssued,
                callbackIsCurrent = gatt != null && gatt === bluetoothGatt,
                callbackKind = kind,
                ffe4Success = status == BluetoothGatt.GATT_SUCCESS,
                reply = callback?.currentPasswordReply(),
            )
            aapsLogger.debug(LTag.PUMPBTCOMM, "PASSWORD: data-channel callback ($kind, status=$status) -> $action")
            when (action) {
                ApexDriverLogic.HandshakeAction.Ignore        -> return
                ApexDriverLogic.HandshakeAction.Promote       -> {
                    passwordGateActive = false
                    promoteConnected(gatt, status)
                }
                ApexDriverLogic.HandshakeAction.RefuseNoLock  -> {
                    passwordGateActive = false
                    callback?.onPasswordCccFailed()
                }
                ApexDriverLogic.HandshakeAction.RefuseAndLock -> {
                    passwordGateActive = false
                    callback?.onPasswordChannelRefused()
                }
            }
        }

        /** Promote the link to CONNECTED and fire onConnect on a worker, after the usual 1 s settle. */
        private fun promoteConnected(gatt: BluetoothGatt?, status: Int) {
            Thread {
                aapsLogger.debug(LTag.PUMPBTCOMM, "Connecting | Notification status: $status")
                gatt?.setCharacteristicNotification(readCharacteristic, true)
                prevCommandMs = SystemClock.uptimeMillis()
                SystemClock.sleep(1000)
                if (_status != Status.DISCONNECTED) {
                    _status = Status.CONNECTED
                    Thread { callback?.onConnect() }.start()
                    aapsLogger.debug(LTag.PUMPBTCOMM, "Connected")
                }
            }.start()
        }

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            super.onCharacteristicRead(gatt, characteristic, status)
            onPumpData(gatt, characteristic, characteristic.value)
        }

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicChanged(gatt, characteristic)
            onPumpData(gatt, characteristic, characteristic.value)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            onPumpData(gatt, characteristic, value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onPumpData(gatt, characteristic, value)
        }

        /**
         * FFC1 is write-with-response; this is its completion latch. Matches [PASSWORD_WRITE_UUID] only -
         * command writes on FFE9 use the same write type and must not touch it - and arms the latch only
         * while the client is still current, so a superseded write never reports a completion the live
         * gate would act on.
         */
        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            if (characteristic?.uuid != PASSWORD_WRITE_UUID) return
            if (gatt == null || gatt !== bluetoothGatt) return
            synchronized(passwordWriteLock) {
                passwordWriteStatus = status
                passwordWriteLock.notifyAll()
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        @Synchronized
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            if (result == null) {
                aapsLogger.error(LTag.PUMPBTCOMM, "Scan results empty $callbackType")
                synchronized (connectionLock) {
                    _status = Status.DISCONNECTED
                }
                return
            }
            // The scan filters are OR-ed and two of them match the generic FFE0/FFE5 UART services, so
            // arbitrary nearby BLE devices land here — including nameless advertisers (device.name is
            // a platform type and really can be null). Just ignore them and keep scanning: dropping
            // _status to DISCONNECTED here aborted the whole connect attempt while leaving the scan
            // running, which in a busy RF environment turned into a scan-forever / never-connect loop.
            val serial = preferences.get(ApexStringKey.SerialNumber)
            val deviceName = result.device.name
            if (!isTargetApexDevice(deviceName, serial)) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "Got non-target device ($deviceName, serial $serial) - ignoring")
                return
            }

            aapsLogger.debug(LTag.PUMPBTCOMM, "Found device ${result.device.name}")
            stopScan()
            preferences.put(ApexStringKey.BluetoothAddress, result.device.address)
            reconnect()
        }

        @SuppressLint("MissingPermission")
        @Synchronized
        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            aapsLogger.error(LTag.PUMPBTCOMM, "Scan failed $errorCode")
            synchronized (connectionLock) {
                _status = Status.DISCONNECTED
            }
            return
        }
    }

    // Built per-scan (not cached) so a changed serial number is reflected — the device name encodes it.
    private fun scanFilters() = listOf(
        ScanFilter.Builder()
            .setDeviceName("APEX${preferences.get(ApexStringKey.SerialNumber)}")
            .build(),
        ScanFilter.Builder()
            .setServiceUuid(READ_SERVICE)
            .build(),
        ScanFilter.Builder()
            .setServiceUuid(WRITE_SERVICE)
            .build(),
    )

    // Built on first use, not in the constructor: the platform builder is a stub on the JVM, so building
    // it eagerly made this class impossible to construct in a unit test. Building it here also keeps it
    // inside the try around startScan, where a throw is handled instead of killing the raw thread.
    private val scanSettings by lazy {
        ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
    }

    enum class Status {
        DISCONNECTED,
        CONNECTING,
        CONNECTED;

        fun toLocalString(rh: ResourceHelper): String = when (this) {
            DISCONNECTED -> rh.gs(R.string.overview_connection_status_disconnected)
            CONNECTING -> rh.gs(R.string.overview_connection_status_connecting)
            CONNECTED -> rh.gs(R.string.overview_connection_status_connected)
        }
    }
}