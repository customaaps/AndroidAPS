package info.nightscout.comboctl.android

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import info.nightscout.comboctl.base.toBluetoothAddress
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import info.nightscout.comboctl.base.ComboIOException
import org.junit.jupiter.api.Assertions.*
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

    @Test
    fun `disconnect interrupts a blocking read without holding the socket lock`() = checkConcurrentDisconnect(read = true)

    @Test
    fun `disconnect interrupts a blocking write without holding the socket lock`() = checkConcurrentDisconnect(read = false)

    private fun checkConcurrentDisconnect(read: Boolean) {
        prepare()
        val created = socket()
        val input = mock(InputStream::class.java)
        val output = mock(OutputStream::class.java)
        whenever(created.inputStream).thenReturn(input)
        whenever(created.outputStream).thenReturn(output)
        whenever(remote.createInsecureRfcommSocketToServiceRecord(any())).thenReturn(created)
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val blockedIo = org.mockito.stubbing.Answer<Any> {
            entered.countDown()
            check(closed.await(3, TimeUnit.SECONDS)) { "Disconnect could not close the stream" }
            throw IOException("stream closed by disconnect")
        }
        if (read) doAnswer(blockedIo).whenever(input).read(any<ByteArray>())
        else doAnswer(blockedIo).whenever(output).write(any<ByteArray>())
        doAnswer { closed.countDown(); null }.whenever(input).close()
        doAnswer { closed.countDown(); null }.whenever(output).close()
        sut.connect()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val io = executor.submit<List<Byte>> {
                if (read) sut.blockingReceive() else { sut.blockingSend(listOf(1)); emptyList() }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            sut.disconnect()
            assertEquals(emptyList<Byte>(), io.get(2, TimeUnit.SECONDS))
            verify(input).close()
            verify(output).close()
            verify(created).close()
            // Already-disconnected calls are harmless too.
            assertEquals(emptyList<Byte>(), sut.blockingReceive())
            sut.blockingSend(listOf(2))
        } finally {
            closed.countDown()
            sut.disconnect()
            executor.shutdownNow()
        }
    }

    @Test
    fun `genuine input and output errors on an active connection are propagated`() {
        prepare()
        val created = socket()
        val input = mock(InputStream::class.java)
        val output = mock(OutputStream::class.java)
        whenever(created.inputStream).thenReturn(input)
        whenever(created.outputStream).thenReturn(output)
        whenever(remote.createInsecureRfcommSocketToServiceRecord(any())).thenReturn(created)
        doThrow(IOException("read failed")).whenever(input).read(any<ByteArray>())
        doThrow(IOException("write failed")).whenever(output).write(any<ByteArray>())
        sut.connect()
        try {
            assertThrows(ComboIOException::class.java) { sut.blockingReceive() }
            assertThrows(ComboIOException::class.java) { sut.blockingSend(listOf(1)) }
        } finally {
            sut.disconnect()
        }
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
