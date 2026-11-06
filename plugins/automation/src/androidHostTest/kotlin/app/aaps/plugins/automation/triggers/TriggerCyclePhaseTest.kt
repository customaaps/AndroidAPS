package app.aaps.plugins.automation.triggers

import app.aaps.core.interfaces.periodcalendar.PeriodCalendar
import app.aaps.core.interfaces.plugin.PluginBase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class TriggerCyclePhaseTest : TriggerTestBase() {

    private lateinit var periodCalendarPlugin: PluginBase
    private lateinit var periodCalendar: PeriodCalendar

    @BeforeEach
    fun setUp() {
        periodCalendarPlugin = mock(extraInterfaces = arrayOf(PeriodCalendar::class))
        periodCalendar = periodCalendarPlugin as PeriodCalendar
        whenever(periodCalendarPlugin.isEnabled()).thenReturn(true)
        whenever(activePlugin.getSpecificPluginsListByInterface(PeriodCalendar::class)).thenReturn(arrayListOf(periodCalendarPlugin))
    }

    @Test
    fun shouldRun_usesPeriodCalendarCurrentPhaseLabel() = runTest {
        whenever(periodCalendar.getCurrentPhaseLabel()).thenReturn("OV")
        val trigger = TriggerCyclePhase(triggerDeps)
        trigger.phaseLabel.value = "OV"

        assertThat(trigger.shouldRun()).isTrue()
    }

    @Test
    fun shouldRun_firesOnceThenSuppressesWhileInSamePhase() = runTest {
        whenever(periodCalendar.getCurrentPhaseLabel()).thenReturn("OV")
        val trigger = TriggerCyclePhase(triggerDeps)
        trigger.phaseLabel.value = "OV"

        // Edge-triggered: fires once on entering the phase, then stays quiet so it
        // does not re-assert (and overwrite) the profile percentage every ~5 min.
        assertThat(trigger.shouldRun()).isTrue()
        assertThat(trigger.shouldRun()).isFalse()
        assertThat(trigger.shouldRun()).isFalse()
    }

    @Test
    fun shouldRun_reArmsAfterLeavingAndReenteringPhase() = runTest {
        val trigger = TriggerCyclePhase(triggerDeps)
        trigger.phaseLabel.value = "OV"

        whenever(periodCalendar.getCurrentPhaseLabel()).thenReturn("OV")
        assertThat(trigger.shouldRun()).isTrue()   // entry fires
        assertThat(trigger.shouldRun()).isFalse()  // suppressed within same phase

        whenever(periodCalendar.getCurrentPhaseLabel()).thenReturn("L2")
        assertThat(trigger.shouldRun()).isFalse()  // left the phase -> re-arm

        whenever(periodCalendar.getCurrentPhaseLabel()).thenReturn("OV")
        assertThat(trigger.shouldRun()).isTrue()   // re-entry fires again
    }

    @Test
    fun phaseLabels_readConfiguredLabelsFromPeriodCalendar() {
        whenever(periodCalendar.getPhaseLabels()).thenReturn(listOf("ME", "OV"))

        assertThat(TriggerCyclePhase(triggerDeps).phaseLabels).containsExactly("ME", "OV").inOrder()
    }
}
