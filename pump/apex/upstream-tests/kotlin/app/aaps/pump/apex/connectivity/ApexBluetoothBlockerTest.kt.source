package app.aaps.pump.apex.connectivity

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.apex.ApexDriverStatus
import app.aaps.pump.apex.connectivity.commands.device.DeviceCommand
import app.aaps.pump.apex.interfaces.ApexBluetoothCallback
import app.aaps.pump.apex.misc.ApexNotifications
import app.aaps.shared.tests.TestBase
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The "Bluetooth is blocked" card must not outlive the driver that raised it.
 *
 * The case this file exists for: the episode state lives in this `@Singleton`, while the only way it
 * was ever ended ran from `checkBT`, which only the service calls. Stop the driver while blocked -
 * switch the Apex plugin off, or pick another pump - and the card kept standing over a pump that is
 * not even selected any more, for the rest of the app's life.
 */
class ApexBluetoothBlockerTest : TestBase() {

    @Mock lateinit var context: Context
    @Mock lateinit var bluetoothManager: BluetoothManager
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var apexDriverStatus: ApexDriverStatus
    @Mock lateinit var apexNotifications: ApexNotifications
    @Mock lateinit var rh: ResourceHelper

    private lateinit var sut: ApexBluetooth

    private val owner = mock<ApexBluetoothCallback>()

    private fun createSut(): ApexBluetooth {
        whenever(context.getSystemService(BluetoothManager::class.java)).thenReturn(bluetoothManager)
        // Nearby devices is not granted, so checkBT() stops at the permission blocker and never asks
        // the adapter anything.
        whenever(context.checkPermission(anyString(), anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_DENIED)
        whenever(rh.gs(anyInt())).thenReturn("blocked")
        return ApexBluetooth(aapsLogger, preferences, context, apexDriverStatus, apexNotifications, rh)
    }

    /** Any command will do: `checkBT` runs first and refuses before the BLE stack is touched. */
    private fun raiseTheBlocker() {
        sut.send(mock<DeviceCommand>())
    }

    @Test
    fun theBlockerCardIsTakenDownWhenTheDriverStops() {
        sut = createSut()
        sut.setCallback(owner)
        raiseTheBlocker()
        verify(apexNotifications, never()).dismiss(anyInt())

        sut.clearCallback(owner)

        verify(apexNotifications).dismiss(ApexNotifications.PERMISSION_MISSING)
    }

    @Test
    fun anOlderCallbackOwnerDoesNotTakeTheCardDown() {
        sut = createSut()
        sut.setCallback(owner)
        raiseTheBlocker()

        // A service that was already replaced letting go of a callback it no longer owns.
        sut.clearCallback(mock<ApexBluetoothCallback>())

        verify(apexNotifications, never()).dismiss(anyInt())
    }

    @Test
    fun aLateConnectAttemptAfterTeardownDoesNotRaiseTheCardAgain() {
        sut = createSut()
        sut.setCallback(owner)
        raiseTheBlocker()
        sut.clearCallback(owner)

        // A thread of the retry loop that has not noticed the teardown yet. Without the guard this
        // would start a second episode, put the card back, and nothing would ever take it down.
        raiseTheBlocker()

        // Counted on the card instead of on the bus: dev4 sent one snackbar event per episode start,
        // this version raises an error toast there, which a JVM test cannot observe. The card post is
        // the other thing an episode start always does, and it counts the same - a second episode
        // would post a second time.
        verify(apexNotifications, times(1)).post(eq(ApexNotifications.PERMISSION_MISSING), anyString(), anyInt(), anyOrNull())
    }
}
