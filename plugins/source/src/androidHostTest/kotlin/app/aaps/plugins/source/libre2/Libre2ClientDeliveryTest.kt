package app.aaps.plugins.source.libre2

import android.content.*
import android.os.*
import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.shared.tests.AAPSLoggerTest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Libre2ClientDeliveryTest {
    private class SensorContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val thread = HandlerThread("Libre2TestSensor").apply { start() }
        val registered = CountDownLatch(1)
        val registrations = java.util.concurrent.LinkedBlockingQueue<Messenger>()
        val acknowledged = CountDownLatch(1)
        @Volatile var parent: Messenger? = null
        val remote = Messenger(object : Handler(thread.looper) {
            override fun handleMessage(message: Message) {
                when (message.what) {
                    Libre2Ipc.REGISTER -> { parent = message.replyTo; registrations.add(message.replyTo); registered.countDown() }
                    Libre2Ipc.ACK_READING -> acknowledged.countDown()
                }
            }
        })
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            connection.onServiceConnected(ComponentName(this, Libre2SensorService::class.java), remote.binder)
            return true
        }
        override fun unbindService(connection: ServiceConnection) = Unit
        fun reading(target: Messenger = requireNotNull(parent), sampleId: Int = 1) {
            val now = System.currentTimeMillis()
            target.send(Message.obtain(null, Libre2Ipc.READING).apply {
                sendingUid = android.os.Process.myUid()
                data = Libre2Reading("E007AABBCCDDEEFF", sampleId, now, now - 86_400_000, 35, 3).toBundle()
            })
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `failed database write is not acknowledged and a retry can succeed`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val sensor = SensorContext()
        val database: PersistenceLayer = mock()
        val attempts = AtomicInteger()
        whenever(database.insertCgmSourceData(any(), any(), any(), any())).thenAnswer {
            if (attempts.incrementAndGet() == 1) throw IOException("database temporarily unavailable")
            PersistenceLayer.TransactionResult<GV>()
        }
        val client = Libre2Client(sensor, database, mock<RxBus>(), AAPSLoggerTest())
        try {
            client.start()
            assertTrue(sensor.registered.await(5, TimeUnit.SECONDS))
            sensor.reading()
            withTimeout(5_000) { client.status.first { it.code == Libre2StatusCode.ERROR } }
            assertFalse(sensor.acknowledged.await(100, TimeUnit.MILLISECONDS))
            sensor.reading()
            assertTrue(sensor.acknowledged.await(5, TimeUnit.SECONDS))
            assertEquals(2, attempts.get())
        } finally {
            client.stop()
            sensor.thread.quitSafely()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `stop waits for an in flight transaction and rejects late readings`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val sensor = SensorContext()
        val database: PersistenceLayer = mock()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        whenever(database.insertCgmSourceData(any(), any(), any(), any())).thenAnswer {
            attempts.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            PersistenceLayer.TransactionResult<GV>()
        }
        val client = Libre2Client(sensor, database, mock<RxBus>(), AAPSLoggerTest())
        try {
            client.start()
            assertTrue(sensor.registered.await(5, TimeUnit.SECONDS))
            val oldReceiver = requireNotNull(sensor.registrations.poll(5, TimeUnit.SECONDS))
            sensor.reading()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val stopping = launch(start = CoroutineStart.UNDISPATCHED) { client.stop() }
            assertFalse(stopping.isCompleted)
            sensor.reading(oldReceiver, sampleId = 2)
            release.countDown()
            withTimeout(5_000) { stopping.join() }
            assertEquals(1, attempts.get())
            assertFalse(sensor.acknowledged.await(100, TimeUnit.MILLISECONDS))
            assertEquals(Libre2StatusCode.STOPPED, client.status.value.code)
            client.start()
            val newReceiver = requireNotNull(sensor.registrations.poll(5, TimeUnit.SECONDS))
            assertNotSame(oldReceiver, newReceiver)
            sensor.reading(oldReceiver, sampleId = 3)
            client.stop()
            assertEquals(1, attempts.get())
        } finally {
            release.countDown()
            client.stop()
            sensor.thread.quitSafely()
            Dispatchers.resetMain()
        }
    }
}
