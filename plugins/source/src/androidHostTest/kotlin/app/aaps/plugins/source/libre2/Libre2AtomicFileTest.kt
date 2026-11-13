package app.aaps.plugins.source.libre2

import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Libre2AtomicFileTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `failed replacement preserves the previously committed file`() {
        val base = File(folder.root, "state.bin")
        val file = Libre2AtomicFile(base)
        file.write(byteArrayOf(1, 2, 3))
        File(base.path + ".pending").mkdir()
        assertThrows(IOException::class.java) { file.write(byteArrayOf(9)) }
        assertArrayEquals(byteArrayOf(1, 2, 3), file.read(100))
        file.write(byteArrayOf(4, 5))
        assertArrayEquals(byteArrayOf(4, 5), Libre2AtomicFile(base).read(100))
    }

    @Test
    fun `pairing unlock counter and opaque state survive repeated atomic saves`() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = folder.root
        }
        val pairing = Libre2Pairing(
            ByteArray(8) { it.toByte() }, byteArrayOf(0, 0, 0x30, 0, 0, 0),
            "AA:BB:CC:DD:EE:FF", byteArrayOf(), 1_800_000_000, false,
            Libre2DecoderState(1_799_900_000, 1_800_000_000, 60, 20_160, 0,
                arrayOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))),
        )
        val store = Libre2PairingStore(context)
        store.save(pairing)
        store.save(pairing.copy(unlockCounter = 2, lastSampleId = 100, lastReadingMillis = 1_800_000_000_000))
        val restored = requireNotNull(Libre2PairingStore(context).load())
        assertEquals(2, restored.unlockCounter)
        assertEquals(100, restored.lastSampleId)
        assertEquals(1_800_000_000_000, restored.lastReadingMillis)
        assertArrayEquals(pairing.uid, restored.uid)
        assertArrayEquals(pairing.state.metadata(), restored.state.metadata())
        for (i in 0..2) assertArrayEquals(pairing.state.arrays[i], restored.state.arrays[i])
    }
}
