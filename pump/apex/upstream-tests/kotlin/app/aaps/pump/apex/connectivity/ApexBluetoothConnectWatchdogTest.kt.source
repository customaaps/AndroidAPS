package app.aaps.pump.apex.connectivity

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.content.Context
import android.content.pm.PackageManager
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.apex.ApexDriverStatus
import app.aaps.pump.apex.misc.ApexNotifications
import app.aaps.pump.apex.utils.keys.ApexStringKey
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.kotlin.whenever

/**
 * A connect attempt must always end, one way or the other.
 *
 * The case this file exists for: nothing limited the scan phase. Android ends an LE scan without a
 * word when the radio goes off, so neither `onScanResult` nor `onScanFailed` arrives, the status
 * stays [ApexBluetooth.Status.CONNECTING], and every way back in - `connect`, the retry loop in the
 * service, `ApexPumpPlugin.connect` - only acts on DISCONNECTED. The pump then stops being dosed
 * until the app is restarted. The same trap catches an attempt that threw after the status was set:
 * a cached address the platform refuses makes `getRemoteDevice` throw.
 */
class ApexBluetoothConnectWatchdogTest : TestBase() {

    @Mock lateinit var context: Context
    @Mock lateinit var bluetoothManager: BluetoothManager
    @Mock lateinit var adapter: BluetoothAdapter
    @Mock lateinit var scanner: BluetoothLeScanner
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var apexDriverStatus: ApexDriverStatus
    @Mock lateinit var rh: ResourceHelper

    /** [address] empty means "scan for the pump", non-empty means "reconnect to this one". */
    private fun createSut(address: String): ApexBluetooth {
        whenever(context.getSystemService(BluetoothManager::class.java)).thenReturn(bluetoothManager)
        whenever(bluetoothManager.adapter).thenReturn(adapter)
        // Nothing blocks the stack, so checkBT() lets the attempt through.
        whenever(context.checkPermission(anyString(), anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_GRANTED)
        whenever(adapter.isEnabled).thenReturn(true)
        // Eight digits, or `connect` stops at its serial check and never starts an attempt at all -
        // the two tests below would then pass on a status that was simply never left.
        whenever(preferences.get(ApexStringKey.SerialNumber)).thenReturn("12345678")
        whenever(preferences.get(ApexStringKey.BluetoothAddress)).thenReturn(address)
        // The real one, not a mock: it only sends on the bus, and nothing here reads a card back.
        val sut = ApexBluetooth(aapsLogger, preferences, context, apexDriverStatus, ApexNotifications(rxBus), rh)
        // Without this the test would sit out a whole minute of the real timeout.
        sut.connectTimeoutMs = 50L
        return sut
    }

    private fun waitForDisconnected(sut: ApexBluetooth): Boolean {
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline) {
            if (sut.status == ApexBluetooth.Status.DISCONNECTED) return true
            Thread.sleep(20)
        }
        return false
    }

    @Test
    fun aScanThatCannotEvenStartDoesNotLeaveTheDriverConnecting() {
        val sut = createSut(address = "")
        whenever(adapter.bluetoothLeScanner).thenReturn(scanner)

        sut.connect()

        // Honest about what this covers: on the JVM the platform scan classes are stubs, so building the
        // scan filters throws before startScan is even reached, and the status is put back by the guard
        // around the scan - not by the watchdog. That still matters (an uncaught throw on this raw thread
        // took the whole app down), but the WATCHDOG itself is covered by the next test, where the throw
        // happens outside that guard.
        assertThat(waitForDisconnected(sut)).isTrue()
    }

    @Test
    fun anAttemptThatThrowsBeforeAnyGattClientExistsDoesNotLeaveTheDriverConnecting() {
        val sut = createSut(address = "not-an-address")
        // A cached address the platform will not take: settings imported from another phone, or a
        // value damaged in storage. The throw happens after the status was already set to CONNECTING.
        whenever(adapter.getRemoteDevice(anyString())).thenThrow(IllegalArgumentException("bad address"))

        runCatching { sut.connect() }

        assertThat(waitForDisconnected(sut)).isTrue()
    }

    @Test
    fun theWatchdogOnlyDropsAnAttemptThatIsReallyStuck() {
        // Still looking for the pump, and this watchdog owns the attempt.
        assertThat(ApexBluetooth.connectAttemptStuck(7, 7, ApexBluetooth.Status.CONNECTING, gattOpen = false)).isTrue()
        // A GATT client is open, so the handshake is running and setupGatt's own watchdog owns it.
        assertThat(ApexBluetooth.connectAttemptStuck(7, 7, ApexBluetooth.Status.CONNECTING, gattOpen = true)).isFalse()
        // The link came up.
        assertThat(ApexBluetooth.connectAttemptStuck(7, 7, ApexBluetooth.Status.CONNECTED, gattOpen = false)).isFalse()
        // Somebody else already started a newer attempt; killing it would be worse than the defect.
        assertThat(ApexBluetooth.connectAttemptStuck(7, 8, ApexBluetooth.Status.CONNECTING, gattOpen = false)).isFalse()
        // Nothing to drop.
        assertThat(ApexBluetooth.connectAttemptStuck(7, 7, ApexBluetooth.Status.DISCONNECTED, gattOpen = false)).isFalse()
    }
}
