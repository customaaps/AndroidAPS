package app.aaps.plugins.source.libre2

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Libre2ConnectionSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun `defaults preserve automatic recovery and leave CPU protection opt in`() {
        val preferences = context.getSharedPreferences("libre2_connection", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val settings = Libre2ConnectionSettings(context)
        assertEquals(Libre2ConnectionOptions(true, false), settings.load())
        settings.save(Libre2ConnectionOptions(false, true))
        assertEquals(Libre2ConnectionOptions(false, true), Libre2ConnectionSettings(context).load())
        preferences.edit().clear().commit()
    }

    @Test fun `options and diagnostic timestamps survive IPC without changing defaults for old messages`() {
        assertEquals(Libre2ConnectionOptions(true, false), Libre2ConnectionOptions.fromBundle(Bundle()))
        val options = Libre2ConnectionOptions(false, true)
        assertEquals(options, Libre2ConnectionOptions.fromBundle(options.toBundle()))
        val diagnostics = Libre2Diagnostics(lastFragment = 1000, lastPacket = 1100, lastDecoded = 1200,
            fragments = 3, packets = 1, decoded = 1, decoderStatus = 0, quality = 0, sampleId = 61,
            reconnects = 2, lastReconnect = 900, reconnectReason = "watchdog", wakeProtectionHeld = true,
            connectionStage = "DISCOVERING", lastGattStatus = 8, lastGattEvent = 1300)
        val bundle = Bundle().also(diagnostics::writeTo)
        assertEquals(diagnostics, Libre2Diagnostics.fromBundle(bundle))
        assertEquals(Libre2Diagnostics(), Libre2Diagnostics.fromBundle(Bundle()))
    }

    @Test fun `CPU protection renews while enabled and releases immediately when stopped`() {
        val handler = Handler(Looper.getMainLooper())
        val protection = Libre2WakeProtection(context, handler) { fail("Unexpected wake-lock failure") }
        assertFalse(protection.isHeld())
        protection.setEnabled(true)
        assertTrue(protection.isHeld())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))
        assertTrue(protection.isHeld())
        protection.setEnabled(false)
        assertFalse(protection.isHeld())
        protection.close()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(10))
        assertFalse(protection.isHeld())
    }

    @Test fun `a power service failure cannot crash the sensor or schedule endless renewals`() {
        val unavailable = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? = if (name == Context.POWER_SERVICE) null else super.getSystemService(name)
        }
        var failures = 0
        val protection = Libre2WakeProtection(unavailable, Handler(Looper.getMainLooper())) { failures++ }
        protection.setEnabled(true)
        assertFalse(protection.isHeld())
        assertEquals(1, failures)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(10))
        assertEquals(1, failures)
        protection.close()
    }
}
