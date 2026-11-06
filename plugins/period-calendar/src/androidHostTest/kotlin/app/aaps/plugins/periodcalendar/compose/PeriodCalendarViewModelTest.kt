package app.aaps.plugins.periodcalendar.compose

import app.aaps.plugins.periodcalendar.model.CyclePhase
import app.aaps.plugins.periodcalendar.services.CycleCounterService
import app.aaps.plugins.periodcalendar.services.CycleNotificationService
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PeriodCalendarViewModelTest : TestBase() {

    @Mock lateinit var cycleCounterService: CycleCounterService
    @Mock lateinit var cycleNotificationService: CycleNotificationService

    @BeforeEach
    fun setUp() {
        whenever(cycleCounterService.getCurrentCycleDay()).thenReturn(0)
        whenever(cycleCounterService.getCurrentPhase()).thenReturn(null)
        whenever(cycleCounterService.phases).thenReturn(
            listOf(
                CyclePhase(1, 5, "ME"),
                CyclePhase(6, 10, "FO"),
            ),
        )
        whenever(cycleNotificationService.notifyCycleStart).thenReturn(false)
        whenever(cycleNotificationService.notifyPhaseChange).thenReturn(false)
    }

    @Test
    fun savePhase_replacesEditedPhaseAtIndexAndResorts() {
        val edited = CyclePhase(11, 13, "OV")
        val viewModel = PeriodCalendarViewModel(cycleCounterService, cycleNotificationService)

        viewModel.savePhase(edited, editIndex = 0)

        val captor = argumentCaptor<List<CyclePhase>>()
        verify(cycleCounterService).phases = captor.capture()
        assertThat(captor.firstValue).containsExactly(
            CyclePhase(6, 10, "FO"),
            CyclePhase(11, 13, "OV"),
        ).inOrder()
    }
}
