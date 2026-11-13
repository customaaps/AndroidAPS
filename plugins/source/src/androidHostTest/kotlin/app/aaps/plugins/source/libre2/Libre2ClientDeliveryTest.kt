package app.aaps.plugins.source.libre2

import android.content.*
import android.os.*
import app.aaps.core.data.model.GV
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
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
        val acknowledgements = java.util.concurrent.LinkedBlockingQueue<String>()
        @Volatile var parent: Messenger? = null
        val remote = Messenger(object : Handler(thread.looper) {
            override fun handleMessage(message: Message) {
                when (message.what) {
                    Libre2Ipc.REGISTER -> { parent = message.replyTo; registrations.add(message.replyTo); registered.countDown() }
                    Libre2Ipc.ACK_READING -> {
                        acknowledgements.add(requireNotNull(message.data.getString("token")))
                        acknowledged.countDown()
                    }
                }
            }
        })
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            connection.onServiceConnected(ComponentName(this, Libre2SensorService::class.java), remote.binder)
            return true
        }
        override fun unbindService(connection: ServiceConnection) = Unit
        fun reading(target: Messenger = requireNotNull(parent), sampleId: Int = 1, timestamp: Long = System.currentTimeMillis()): Libre2Reading {
            val reading = Libre2Reading("E007AABBCCDDEEFF", sampleId, timestamp, timestamp - 86_400_000, 35, 3)
            target.send(Message.obtain(null, Libre2Ipc.READING).apply {
                sendingUid = android.os.Process.myUid()
                data = reading.toBundle()
            })
            shadowOf(Looper.getMainLooper()).idle()
            return reading
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
        val preferences: Preferences = mock()
        whenever(preferences.get(BooleanKey.BgSourceCreateSensorChange)).thenReturn(true)
        val client = Libre2Client(sensor, database, mock<RxBus>(), AAPSLoggerTest(), preferences)
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
    fun `sensor registration disabled still delivers every minute without an insertion event`() = verifySensorRegistration(false)

    @Test
    fun `sensor registration enabled passes sensor start while preserving minute readings`() = verifySensorRegistration(true)

    private fun verifySensorRegistration(enabled: Boolean) = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val sensor = SensorContext()
        val database: PersistenceLayer = mock()
        val preferences: Preferences = mock()
        whenever(preferences.get(BooleanKey.BgSourceCreateSensorChange)).thenReturn(enabled)
        whenever(database.insertCgmSourceData(any(), any(), any(), anyOrNull())).thenReturn(PersistenceLayer.TransactionResult<GV>())
        val client = Libre2Client(sensor, database, mock<RxBus>(), AAPSLoggerTest(), preferences)
        try {
            client.start()
            assertTrue(sensor.registered.await(5, TimeUnit.SECONDS))
            val firstTime = System.currentTimeMillis() - 120_000
            val sent = List(3) { index ->
                sensor.reading(sampleId = index + 1, timestamp = firstTime + 60_000L * index).also {
                    assertEquals(it.token, sensor.acknowledgements.poll(5, TimeUnit.SECONDS))
                }
            }
            val values = argumentCaptor<List<GV>>()
            if (enabled) verify(database, times(3)).insertCgmSourceData(any(), values.capture(), any(), any())
            else verify(database, times(3)).insertCgmSourceData(any(), values.capture(), any(), isNull())
            assertEquals(sent.map { it.timestamp }, values.allValues.map { it.single().timestamp })
            assertEquals(listOf(35.0, 35.0, 35.0), values.allValues.map { it.single().value })
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
        val preferences: Preferences = mock()
        whenever(preferences.get(BooleanKey.BgSourceCreateSensorChange)).thenReturn(true)
        val client = Libre2Client(sensor, database, mock<RxBus>(), AAPSLoggerTest(), preferences)
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
