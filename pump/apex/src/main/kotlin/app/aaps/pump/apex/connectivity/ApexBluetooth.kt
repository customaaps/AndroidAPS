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
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.keys.Preferences
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.utils.toHex
import app.aaps.pump.apex.R
import app.aaps.pump.apex.connectivity.commands.device.DeviceCommand
import app.aaps.pump.apex.connectivity.commands.pump.PumpCommand
import app.aaps.pump.apex.utils.keys.ApexStringKey
import kotlinx.coroutines.sync.Mutex
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Singleton
class ApexBluetooth @Inject constructor(
    val aapsLogger: AAPSLogger,
    val preferences: Preferences,
    val context: Context,
    val rxBus: RxBus,
) : ScanCallback() {
    companion object {
        private val READ_SERVICE = ParcelUuid.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        private val WRITE_SERVICE = ParcelUuid.fromString("0000FFE5-0000-1000-8000-00805F9B34FB")

        private val READ_UUID = UUID.fromString("0000FFE4-0000-1000-8000-00805F9B34FB")
        private val WRITE_UUID = UUID.fromString("0000FFE9-0000-1000-8000-00805F9B34FB")
        private val CCC_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        private const val WRITE_DELAY_MS = 1000
    }

    private val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
    private var callback: ApexBluetoothCallback? = null

    private var bluetoothDevice: BluetoothDevice? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null

    private var mtu: Int = 512

    private val readMutex = Mutex()
    private var lastCommand: PumpCommand? = null
    private var _status: Status = Status.DISCONNECTED

    val status: Status
        get() = _status

    fun setCallback(callback: ApexBluetoothCallback) {
        this.callback = callback
    }

    private var prevCommandMs = SystemClock.uptimeMillis()

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    @Synchronized
    fun send(command: DeviceCommand) {
        if (checkBT())  {
            aapsLogger.error(LTag.PUMPBTCOMM, "Tried to invoke command but BT is not ready")
            return
        }
        if (status != Status.CONNECTED) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Tried to invoke command but pump is disconnected")
            return
        }

        val delta = WRITE_DELAY_MS - SystemClock.uptimeMillis() + prevCommandMs
        if (delta > 0) SystemClock.sleep(delta)
        prevCommandMs = SystemClock.uptimeMillis()

        val data = command.serialize()
        var start = 0
        while (start < data.size) {
            val end = min(start + mtu, data.size)
            val chunk = data.copyOfRange(start, end)

            aapsLogger.debug(LTag.PUMPBTCOMM, "DEVICE[$start] -> ${chunk.toHex()}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGatt!!.writeCharacteristic(
                    writeCharacteristic!!,
                    chunk,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                writeCharacteristic!!.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                writeCharacteristic!!.setValue(chunk)
                bluetoothGatt!!.writeCharacteristic(writeCharacteristic!!)
            }

            start = end
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun connect() {
        if (_status != Status.DISCONNECTED) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Already connecting! Ignoring repeated request")
            return
        }

        aapsLogger.debug(LTag.PUMPBTCOMM, "Connect")
        if (preferences.get(ApexStringKey.SerialNumber).isEmpty()) return
        if (checkBT()) return
        _status = Status.CONNECTING
        if (preferences.get(ApexStringKey.BluetoothAddress).isNotEmpty()) return reconnect()

        aapsLogger.debug(LTag.PUMPBTCOMM, "Scan started")
        bluetoothAdapter.bluetoothLeScanner.startScan(
            listOf(
                ScanFilter.Builder()
                    .setDeviceName("APEX${preferences.get(ApexStringKey.SerialNumber)}")
                    .build(),
                ScanFilter.Builder()
                    .setServiceUuid(READ_SERVICE)
                    .build(),
                ScanFilter.Builder()
                    .setServiceUuid(WRITE_SERVICE)
                    .build(),
            ),
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build(), this
        )
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun disconnect() {
        if (bluetoothGatt == null && status != Status.CONNECTING) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Already connecting! Ignoring repeated request")
            return
        }

        aapsLogger.debug(LTag.PUMPBTCOMM, "Disconnect")
        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTING))

        stopScan()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null

        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED))
    }

    private fun checkBT(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ToastUtils.errorToast(context, context.getString(app.aaps.core.ui.R.string.need_connect_permission))
            aapsLogger.error(LTag.PUMPBTCOMM, "No Bluetooth permission!")
            return true
        }

        if (bluetoothAdapter == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "No Bluetooth adapter!")
            return true
        }
        return false
    }

    @Synchronized
    @SuppressLint("MissingPermission")
    private fun setupGatt() {
        // Do not allow multiple GATTs
        if (bluetoothGatt != null) {
            bluetoothGatt?.close()
            bluetoothGatt = null
            SystemClock.sleep(50)
        }

        bluetoothGatt = bluetoothDevice!!.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                super.onConnectionStateChange(gatt, status, newState)
                when (newState) {
                    BluetoothGatt.STATE_DISCONNECTED -> {
                        rxBus.send(EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTED))
                        _status = Status.DISCONNECTED
                        aapsLogger.debug(LTag.PUMPBTCOMM, "Disconnected")
                        Thread { callback?.onDisconnect() }.start()
                        bluetoothGatt?.close()
                        bluetoothGatt = null
                    }
                    BluetoothGatt.STATE_CONNECTED -> {
                        aapsLogger.debug(LTag.PUMPBTCOMM, "Connecting | Discovering services")
                        bluetoothGatt?.discoverServices()
                    }
                }
            }

            @Suppress("DEPRECATION")
            override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                super.onMtuChanged(gatt, mtu, status)
                if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) {
                    aapsLogger.error(LTag.PUMPBTCOMM, "Failed to update MTU")
                    disconnect()
                    return
                }

                Thread {
                    this@ApexBluetooth.mtu = mtu
                    aapsLogger.debug(LTag.PUMPBTCOMM, "Connecting | Updated MTU=$mtu, requesting notification")

                    writeCharacteristic = gatt.getService(WRITE_SERVICE.uuid).getCharacteristic(WRITE_UUID)
                    readCharacteristic = gatt.getService(READ_SERVICE.uuid).getCharacteristic(READ_UUID)
                    gatt.setCharacteristicNotification(readCharacteristic, true)

                    val ccc = readCharacteristic!!.getDescriptor(CCC_UUID)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(ccc)
                    }
                }.start()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    aapsLogger.error(LTag.PUMPBTCOMM, "Failed to discover services")
                    disconnect()
                    return
                }

                aapsLogger.debug(LTag.PUMPBTCOMM, "Connecting | Requested services, requesting MTU")
                gatt.requestMtu(512)
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
                super.onDescriptorWrite(gatt, descriptor, status)
                Thread {
                    aapsLogger.debug(LTag.PUMPBTCOMM, "Connecting | Notification status: $status")
                    gatt?.setCharacteristicNotification(readCharacteristic, true)
                    prevCommandMs = SystemClock.uptimeMillis()
                    SystemClock.sleep(1000)
                    _status = Status.CONNECTED
                    Thread { callback?.onConnect() }.start()
                    aapsLogger.debug(LTag.PUMPBTCOMM, "Connected")
                }.start()
            }

            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                super.onCharacteristicRead(gatt, characteristic, status)
                onPumpData(characteristic, characteristic.value)
            }

            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                super.onCharacteristicChanged(gatt, characteristic)
                onPumpData(characteristic, characteristic.value)
            }
        }, BluetoothDevice.TRANSPORT_LE)

        if (bluetoothGatt == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Connecting | Failed to set up GATT")
            _status = Status.DISCONNECTED
            return
        }

        Thread {
            SystemClock.sleep(25000)
            if (status == Status.CONNECTING) {
                aapsLogger.error(LTag.PUMPBTCOMM, "Connecting | Timed out setting up GATT")
                bluetoothGatt?.close()
                bluetoothGatt = null
                _status = Status.DISCONNECTED
            }
        }.start()
    }

    private fun onPumpData(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "PUMP <- ${value.toHex()}")
        when (characteristic.uuid) {
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
    @Synchronized
    private fun reconnect() {
        aapsLogger.debug(LTag.PUMPBTCOMM, "Connecting | Setting up GATT")
        bluetoothDevice = bluetoothAdapter!!.getRemoteDevice(preferences.get(ApexStringKey.BluetoothAddress))
        setupGatt()
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun stopScan() {
        aapsLogger.debug(LTag.PUMPBTCOMM, "Scan stopped")
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    override fun onScanResult(callbackType: Int, result: ScanResult?) {
        super.onScanResult(callbackType, result)
        if (result == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Scan results empty $callbackType")
            _status = Status.DISCONNECTED
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
        _status = Status.DISCONNECTED
        return
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