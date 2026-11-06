package app.aaps.plugins.automation.triggers

import app.aaps.core.interfaces.periodcalendar.PeriodCalendar
import app.aaps.core.interfaces.plugin.PluginBase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class TriggerCycleDayTest : TriggerTestBase() {

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
    fun shouldRun_usesPeriodCalendarCurrentDay() = runTest {
        whenever(periodCalendar.getCurrentCycleDay()).thenReturn(7)
        val trigger = TriggerCycleDay(triggerDeps)
        trigger.day.value = 7

        assertThat(trigger.shouldRun()).isTrue()
    }

    @Test
    fun shouldRun_falseWhenCycleNotStarted() = runTest {
        whenever(periodCalendar.getCurrentCycleDay()).thenReturn(0)
        val trigger = TriggerCycleDay(triggerDeps)
        trigger.day.value = 1

        assertThat(trigger.shouldRun()).isFalse()
    }

    @Test
    fun shouldRun_firesOnceThenSuppressesWhileSameDay() = runTest {
        whenever(periodCalendar.getCurrentCycleDay()).thenReturn(7)
        val trigger = TriggerCycleDay(triggerDeps)
        trigger.day.value = 7

        // Edge-triggered: one shot per cycle day, so a profile switch is not
        // re-applied every ~5 min for the whole day.
        assertThat(trigger.shouldRun()).isTrue()
        assertThat(trigger.shouldRun()).isFalse()
        assertThat(trigger.shouldRun()).isFalse()
    }

    @Test
    fun shouldRun_reArmsAfterDayChangesAndReturns() = runTest {
        val trigger = TriggerCycleDay(triggerDeps)
        trigger.day.value = 7

        whenever(periodCalendar.getCurrentCycleDay()).thenReturn(7)
        assertThat(trigger.shouldRun()).isTrue()   // entry fires
        assertThat(trigger.shouldRun()).isFalse()  // suppressed same day

        whenever(periodCalendar.getCurrentCycleDay()).thenReturn(8)
        assertThat(trigger.shouldRun()).isFalse()  // day advanced -> re-arm

        whenever(periodCalendar.getCurrentCycleDay()).thenReturn(7)
        assertThat(trigger.shouldRun()).isTrue()   // next cycle, day 7 again fires
    }
}
