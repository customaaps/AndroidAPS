package app.aaps.plugins.source.libre2

import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Libre2BluetoothRecoveryTest {
    private val context = mock<Context>()
    private val manager = mock<BluetoothManager>()
    private val adapter = mock<BluetoothAdapter>()
    private val device = mock<BluetoothDevice>()
    private val gatt = mock<BluetoothGatt>()
    private val handler = Handler(Looper.getMainLooper())
    private val callback = argumentCaptor<BluetoothGattCallback>()
    private val failures = mutableListOf<Exception>()
    private var waits = 0
    private val stages = mutableListOf<String>()

    private fun connection(autoConnect: Boolean): Libre2Bluetooth {
        whenever(context.getSystemService(BluetoothManager::class.java)).thenReturn(manager)
        whenever(manager.adapter).thenReturn(adapter)
        whenever(adapter.isEnabled).thenReturn(true)
        whenever(adapter.getRemoteDevice(any<String>())).thenReturn(device)
        whenever(device.connectGatt(eq(context), eq(autoConnect), callback.capture(), eq(BluetoothDevice.TRANSPORT_LE))).thenReturn(gatt)
        whenever(gatt.discoverServices()).thenReturn(true)
        val pairing = Libre2Pairing(ByteArray(8), ByteArray(6), "00:11:22:33:44:55",
            ByteArray(12), 1, true, Libre2DecoderState())
        return Libre2Bluetooth(context, handler, pairing, { 0 }, {}, {}, failures::add,
            autoConnect = autoConnect, onStage = { stage, _ -> stages += stage }, onWaiting = { waits++ })
    }

    private fun event(status: Int, state: Int) {
        callback.firstValue.onConnectionStateChange(gatt, status, state)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `auto connect waits without a handshake deadline until connected`() {
        val connection = connection(true)
        connection.connect()
        assertTrue(connection.isWaiting)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
        assertTrue(failures.isEmpty())
        verify(gatt, never()).close()
        event(0, BluetoothProfile.STATE_CONNECTED)
        assertFalse(connection.isWaiting)
        verify(gatt).discoverServices()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
        assertTrue(failures.single().message!!.contains("DISCOVERING"))
    }

    @Test fun `direct connection still times out`() {
        val connection = connection(false)
        connection.connect()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
        assertTrue(failures.single().message!!.contains("CONNECTING"))
        verify(gatt).close()
    }

    @Test fun `link timeout retains auto connect GATT and rediscovers services on reconnect`() {
        val connection = connection(true)
        connection.connect()
        event(0, BluetoothProfile.STATE_CONNECTED)
        event(8, BluetoothProfile.STATE_DISCONNECTED)
        assertTrue(connection.isWaiting)
        assertEquals(1, waits)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
        assertTrue(failures.isEmpty())
        verify(gatt, never()).close()
        event(0, BluetoothProfile.STATE_CONNECTED)
        verify(gatt, times(2)).discoverServices()
        connection.close()
        verify(gatt).close()
    }

    @Test fun `closing an awaiting connection ignores queued callbacks and cancels all deadlines`() {
        val connection = connection(true)
        connection.connect()
        callback.firstValue.onConnectionStateChange(gatt, 0, BluetoothProfile.STATE_CONNECTED)
        connection.close()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
        assertTrue(failures.isEmpty())
        verify(gatt, never()).discoverServices()
        verify(gatt, times(1)).close()
    }

    @Test fun `GATT stack errors release the client for a fresh retry`() {
        val connection = connection(true)
        connection.connect()
        event(133, BluetoothProfile.STATE_DISCONNECTED)
        assertEquals(1, failures.size)
        verify(gatt).close()
    }

    @Test fun `disabled recovery does not retain auto connect after disconnect`() {
        val connection = connection(true)
        connection.connect()
        connection.setAutoRecovery(false)
        event(8, BluetoothProfile.STATE_DISCONNECTED)
        assertEquals(1, failures.size)
        verify(gatt).close()
    }
}
