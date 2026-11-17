package info.nightscout.comboctl.android

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import info.nightscout.comboctl.base.toBluetoothAddress
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

class AndroidBluetoothDeviceTest {
    private val context = mock(Context::class.java)
    private val adapter = mock(BluetoothAdapter::class.java)
    private val remote = mock(BluetoothDevice::class.java)
    private val sut = AndroidBluetoothDevice(context, adapter, "00:0E:2F:F9:D6:E5".toBluetoothAddress())

    private fun socket(): BluetoothSocket = mock(BluetoothSocket::class.java).also {
        whenever(it.inputStream).thenReturn(ByteArrayInputStream(byteArrayOf()))
        whenever(it.outputStream).thenReturn(ByteArrayOutputStream())
    }

    private fun prepare() {
        whenever(adapter.getRemoteDevice(any<String>())).thenReturn(remote)
    }

    @Test
    fun `failed socket is closed before next attempt connects`() {
        prepare()
        val failed = socket()
        val working = socket()
        whenever(remote.createInsecureRfcommSocketToServiceRecord(any())).thenReturn(failed, working)
        doThrow(IOException("connection failed")).whenever(failed).connect()
        sut.connect()
        val order = inOrder(failed, working)
        order.verify(failed).connect()
        order.verify(failed).close()
        order.verify(working).connect()
        sut.disconnect()
        verify(working).close()
    }

    @Test
    fun `cancellation during socket creation closes unpublished socket without connecting`() {
        prepare()
        val created = socket()
        whenever(remote.createInsecureRfcommSocketToServiceRecord(any())).thenAnswer {
            sut.disconnect()
            created
        }
        sut.connect()
        verify(created).close()
        verify(created, never()).connect()
        verify(remote, times(1)).createInsecureRfcommSocketToServiceRecord(any())
    }

    @Test
    fun `cancellation of blocking connect closes socket and prevents retries`() {
        prepare()
        val created = socket()
        whenever(remote.createInsecureRfcommSocketToServiceRecord(any())).thenReturn(created)
        doAnswer {
            sut.disconnect()
            throw IOException("socket closed")
        }.whenever(created).connect()
        sut.connect()
        verify(created, atLeastOnce()).close()
        verify(remote, times(1)).createInsecureRfcommSocketToServiceRecord(any())
    }
}
