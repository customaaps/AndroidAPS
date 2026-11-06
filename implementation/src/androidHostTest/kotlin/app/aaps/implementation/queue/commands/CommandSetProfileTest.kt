package app.aaps.implementation.queue.commands

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.pump.PumpWithConcentration
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.queue.cancel
import app.aaps.core.interfaces.smsCommunicator.SmsCommunicator
import app.aaps.implementation.pump.PumpEnactResultObject
import app.aaps.shared.tests.TestBaseWithProfile
import app.aaps.shared.tests.generatedTextResolver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CommandSetProfileTest : TestBaseWithProfile() {

    @Mock lateinit var smsCommunicator: SmsCommunicator
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var commandQueue: CommandQueue

    /** Real English for the Named strings; the mocked rh still answers the resource ids below. */
    private val text by lazy { generatedTextResolver(rh) }

    private fun newCommand() =
        CommandSetProfile(
            aapsLogger, text, activePlugin, dateUtil, commandQueue, persistenceLayer,
            pumpEnactResultProvider::invoke, effectiveProfile
        )

    @Test
    fun `execute returns success not enacted when profile already set`() = runTest {
        whenever(commandQueue.isThisProfileSet(effectiveProfile)).thenReturn(true)
        whenever(persistenceLayer.getEffectiveProfileSwitchActiveAt(anyLong())).thenReturn(effectiveProfileSwitch)
        whenever(activePlugin.activePump).thenReturn(testPumpPlugin)

        val result = newCommand().execute()

        assertThat(result.success).isTrue()
        assertThat(result.enacted).isFalse()
        verify(smsCommunicator, never()).sendNotificationToAllNumbers(any())
    }

    @Test
    fun `execute calls pump setNewBasalProfile when profile differs`() = runTest {
        val pumpResult = PumpEnactResultObject(text).success(true).enacted(true)
        val pump = mock<PumpWithConcentration> {
            on { setNewBasalProfile(effectiveProfile) } doReturn pumpResult
        }
        whenever(commandQueue.isThisProfileSet(effectiveProfile)).thenReturn(false)
        whenever(activePlugin.activePump).thenReturn(pump)
        whenever(persistenceLayer.getEffectiveProfileSwitchActiveAt(anyLong())).thenReturn(null)

        val result = newCommand().execute()

        assertThat(result).isSameInstanceAs(pumpResult)
    }

    @Test
    fun `successful low-level profile write leaves user notification to the queue`() = runTest {
        val pumpResult = PumpEnactResultObject(text).success(true).enacted(true)
        val pump = mock<PumpWithConcentration> {
            on { setNewBasalProfile(effectiveProfile) } doReturn pumpResult
        }
        whenever(commandQueue.isThisProfileSet(effectiveProfile)).thenReturn(false)
        whenever(activePlugin.activePump).thenReturn(pump)
        whenever(persistenceLayer.getEffectiveProfileSwitchActiveAt(anyLong())).thenReturn(effectiveProfileSwitch)

        assertThat(newCommand().execute()).isSameInstanceAs(pumpResult)
        verify(smsCommunicator, never()).sendNotificationToAllNumbers(any())
    }

    @Test
    fun `executeAndComplete completes with execute result`() = runTest {
        whenever(commandQueue.isThisProfileSet(effectiveProfile)).thenReturn(true)
        whenever(persistenceLayer.getEffectiveProfileSwitchActiveAt(anyLong())).thenReturn(effectiveProfileSwitch)
        whenever(activePlugin.activePump).thenReturn(testPumpPlugin)
        val command = newCommand()

        command.executeAndComplete()

        val received = command.completion.await()
        assertThat(received.success).isTrue()
        assertThat(received.enacted).isFalse()
    }

    @Test
    fun `cancel completes with success by default`() = runTest {
        whenever(rh.gs(app.aaps.core.ui.R.string.command_replaced)).thenReturn("replaced")
        val command = newCommand()

        command.cancel(app.aaps.core.ui.R.string.command_replaced)

        val received = command.completion.await()
        assertThat(received.success).isTrue()
    }

    @Test
    fun `cancel completes with failure when success=false`() = runTest {
        whenever(rh.gs(app.aaps.core.ui.R.string.command_replaced)).thenReturn("replaced")
        val command = newCommand()

        command.cancel(app.aaps.core.ui.R.string.command_replaced, success = false)

        val received = command.completion.await()
        assertThat(received.success).isFalse()
    }

    @Test
    fun `commandType is BASAL_PROFILE`() {
        assertThat(newCommand().commandType).isEqualTo(Command.CommandType.BASAL_PROFILE)
    }

    @Test
    fun `log is SET PROFILE`() {
        assertThat(newCommand().log()).isEqualTo("SET PROFILE")
    }
}
