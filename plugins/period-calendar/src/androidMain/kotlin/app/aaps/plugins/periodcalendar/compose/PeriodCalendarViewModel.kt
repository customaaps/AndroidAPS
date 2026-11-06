package app.aaps.plugins.periodcalendar.compose

import androidx.lifecycle.ViewModel
import app.aaps.plugins.periodcalendar.model.CycleInterval
import app.aaps.plugins.periodcalendar.model.CyclePhase
import app.aaps.plugins.periodcalendar.services.CycleCounterService
import app.aaps.plugins.periodcalendar.services.CycleNotificationService
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PeriodCalendarUiState(
    val currentCycleDay: Int = 0,
    val currentPhase: CyclePhase? = null,
    val phases: List<CyclePhase> = emptyList(),
    val notifyCycleStart: Boolean = false,
    val notifyPhaseChange: Boolean = false,
    val cycleIntervals: List<CycleInterval> = emptyList(),
    val averageCycleLength: Double = 0.0,
)

@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class PeriodCalendarViewModel @Inject constructor(
    private val cycleCounterService: CycleCounterService,
    private val cycleNotificationService: CycleNotificationService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PeriodCalendarUiState())
    val uiState: StateFlow<PeriodCalendarUiState> = _uiState.asStateFlow()

    init {
        loadState()
    }

    private fun loadState() {
        _uiState.update {
            it.copy(
                currentCycleDay = cycleCounterService.getCurrentCycleDay(),
                currentPhase = cycleCounterService.getCurrentPhase(),
                phases = cycleCounterService.phases,
                notifyCycleStart = cycleNotificationService.notifyCycleStart,
                notifyPhaseChange = cycleNotificationService.notifyPhaseChange,
                cycleIntervals = cycleCounterService.getCycleIntervals(),
                averageCycleLength = cycleCounterService.getAverageCycleLength(),
            )
        }
    }

    fun resetCycle() {
        cycleCounterService.resetCycle()
        loadState()
    }

    fun setCycleDay(day: Int) {
        cycleCounterService.setCycleDay(day)
        loadState()
    }

    fun clearHistory() {
        cycleCounterService.clearHistory()
        loadState()
    }

    fun savePhase(
        phase: CyclePhase,
        editIndex: Int?,
    ) {
        val phases = cycleCounterService.phases.toMutableList()
        if (editIndex != null && editIndex in phases.indices) {
            phases[editIndex] = phase
        } else {
            phases.add(phase)
        }
        phases.sortBy { it.dayFrom }
        cycleCounterService.phases = phases
        loadState()
    }

    fun deletePhase(index: Int) {
        val phases = cycleCounterService.phases.toMutableList()
        if (index in phases.indices) {
            phases.removeAt(index)
            cycleCounterService.phases = phases
        }
        loadState()
    }

    fun toggleNotifyCycleStart(value: Boolean) {
        cycleNotificationService.notifyCycleStart = value
        _uiState.update { it.copy(notifyCycleStart = value) }
    }

    fun toggleNotifyPhaseChange(value: Boolean) {
        cycleNotificationService.notifyPhaseChange = value
        _uiState.update { it.copy(notifyPhaseChange = value) }
    }
}
