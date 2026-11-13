package app.aaps.plugins.source.libre2

import android.content.ContextWrapper
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Libre2DeliveryTest {
    @get:Rule val folder = TemporaryFolder()
    private val now = 1_800_000_000_000L
    private val context get() = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir(): File = folder.root
    }
    private fun reading(id: Int = 1, timestamp: Long = now) =
        Libre2Reading("E007AABBCCDDEEFF", id, timestamp, now - 86_400_000, 35, 2)

    @Test
    fun `unacknowledged readings survive restart and wrong acknowledgement cannot delete them`() {
        val first = reading()
        Libre2DeliveryStore(context).add(first, now)
        val restored = Libre2DeliveryStore(context)
        assertEquals(first, restored.next(now))
        assertNull(restored.acknowledge("unrelated"))
        assertEquals(first, Libre2DeliveryStore(context).next(now))
        assertEquals(first, restored.acknowledge(first.token))
        assertNull(Libre2DeliveryStore(context).next(now))
    }

    @Test
    fun `retry preserves the original glucose timestamp and token`() {
        val first = reading()
        val store = Libre2DeliveryStore(context)
        store.add(first, now)
        val later = reading(timestamp = now + 60_000)
        assertEquals(first, store.add(later, now + 60_000))
        assertEquals(first, Libre2DeliveryStore(context).next(now + 60_000))
    }

    @Test
    fun `pending data drains in order and a new sensor can reuse a sample number`() {
        val store = Libre2DeliveryStore(context)
        val first = reading()
        val second = reading(2, now + 60_000)
        val otherSensor = reading().copy(sensorId = "E007112233445566")
        store.add(first, now)
        store.add(second, now + 60_000)
        store.add(otherSensor, now + 60_000)
        assertEquals(first, store.next(now + 60_000))
        store.acknowledge(first.token)
        assertEquals(second, store.next(now + 60_000))
        store.acknowledge(second.token)
        assertEquals(otherSensor, store.next(now + 60_000))
    }

    @Test
    fun `stale and future queue data is pruned without retimestamping it`() {
        val store = Libre2DeliveryStore(context)
        store.add(reading(), now)
        assertNull(store.next(now + 86_400_001))
        store.add(reading(2), now)
        assertNull(store.next(now - 60_001))
        assertNull(Libre2DeliveryStore(context).next(now))
    }

    @Test
    fun `invalid or corrupted queue contents are rejected`() {
        val encoded = Libre2DeliveryCodec.encode(listOf(reading()))
        assertEquals(listOf(reading()), Libre2DeliveryCodec.decode(encoded))
        val corrupt = encoded.copyOf().also { it[24] = (it[24].toInt() xor 1).toByte() }
        for (bad in listOf(corrupt, encoded.copyOf(10), encoded + byteArrayOf(0))) {
            assertThrows(IllegalArgumentException::class.java) { Libre2DeliveryCodec.decode(bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { Libre2DeliveryCodec.encode(listOf(reading().copy(mgdl = 0))) }
    }

    @Test
    fun `IPC checks the identity and retains genuine low readings`() {
        val original = reading()
        val message = original.toBundle()
        assertEquals(original, message.libre2ReadingOrNull())
        message.putInt("sampleId", 50)
        assertNull(message.libre2ReadingOrNull())
        assertNull(Bundle().libre2ReadingOrNull())
    }
}
