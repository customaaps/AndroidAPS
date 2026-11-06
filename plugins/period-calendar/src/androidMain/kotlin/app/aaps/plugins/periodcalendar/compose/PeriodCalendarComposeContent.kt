package app.aaps.plugins.periodcalendar.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SetMeal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.compose.metroViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.ui.compose.ComposablePluginContent
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.plugins.periodcalendar.R
import app.aaps.core.ui.R as CoreUiR
import app.aaps.plugins.periodcalendar.model.CycleInterval
import app.aaps.plugins.periodcalendar.model.CyclePhase
import java.text.DateFormat
import java.util.Date

class PeriodCalendarComposeContent : ComposablePluginContent {

    @Composable
    override fun Render(
        setToolbarConfig: (ToolbarConfig) -> Unit,
        onNavigateBack: () -> Unit,
        onSettings: (() -> Unit)?
    ) {
        val viewModel: PeriodCalendarViewModel = metroViewModel()
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val title = stringResource(R.string.period_calendar)

        LaunchedEffect(Unit) {
            setToolbarConfig(
                ToolbarConfig(
                    title = title,
                    navigationIcon = {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(CoreUiR.string.back))
                    },
                    actions = {}
                )
            )
        }

        PeriodCalendarScreen(
            state = state,
            onResetCycle = viewModel::resetCycle,
            onSetCycleDay = viewModel::setCycleDay,
            onClearHistory = viewModel::clearHistory,
            onSavePhase = viewModel::savePhase,
            onDeletePhase = viewModel::deletePhase,
            onToggleNotifyCycleStart = viewModel::toggleNotifyCycleStart,
            onToggleNotifyPhaseChange = viewModel::toggleNotifyPhaseChange,
            onNavigateBack = onNavigateBack
        )
    }
}

@Composable
fun PeriodCalendarScreen(
    state: PeriodCalendarUiState,
    onResetCycle: () -> Unit,
    onSetCycleDay: (Int) -> Unit,
    onClearHistory: () -> Unit,
    onSavePhase: (CyclePhase, Int?) -> Unit,
    onDeletePhase: (Int) -> Unit,
    onToggleNotifyCycleStart: (Boolean) -> Unit,
    onToggleNotifyPhaseChange: (Boolean) -> Unit,
    onNavigateBack: () -> Unit
) {
    var showSetDayDialog by remember { mutableStateOf(false) }
    var showPhaseDialog by remember { mutableStateOf(false) }
    var editingPhaseIndex by remember { mutableIntStateOf(-1) }
    var showConfirmReset by remember { mutableStateOf(false) }
    var showConfirmClear by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CycleDayCard(state)
            }

            item {
                ActionButtons(
                    onResetClick = { showConfirmReset = true },
                    onSetDayClick = { showSetDayDialog = true },
                    onClearHistoryClick = { showConfirmClear = true }
                )
            }

            item {
                CycleHistorySection(
                    intervals = state.cycleIntervals,
                    averageCycleLength = state.averageCycleLength
                )
            }

            item {
                Text(
                    text = "Cycle Phases",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            itemsIndexed(state.phases) { index, phase ->
                PhaseCard(
                    phase = phase,
                    onEdit = {
                        editingPhaseIndex = index
                        showPhaseDialog = true
                    },
                    onDelete = { onDeletePhase(index) }
                )
            }

            item {
                Button(
                    onClick = {
                        editingPhaseIndex = -1
                        showPhaseDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.add_phase))
                }
            }

            item {
                NotificationSettings(
                    notifyCycleStart = state.notifyCycleStart,
                    notifyPhaseChange = state.notifyPhaseChange,
                    onToggleNotifyCycleStart = onToggleNotifyCycleStart,
                    onToggleNotifyPhaseChange = onToggleNotifyPhaseChange
                )
            }
        }
    }

    if (showSetDayDialog) {
        SetDayDialog(
            onDismiss = { showSetDayDialog = false },
            onConfirm = { day ->
                onSetCycleDay(day)
                showSetDayDialog = false
            }
        )
    }

    if (showPhaseDialog) {
        val phaseToEdit = if (editingPhaseIndex >= 0) state.phases.getOrNull(editingPhaseIndex) else null
        PhaseConfigDialog(
            phase = phaseToEdit,
            onDismiss = { showPhaseDialog = false },
            onSave = { phase ->
                onSavePhase(phase, editingPhaseIndex.takeIf { it >= 0 })
                showPhaseDialog = false
            }
        )
    }

    if (showConfirmReset) {
        AlertDialog(
            onDismissRequest = { showConfirmReset = false },
            title = { Text(stringResource(R.string.cycle_reset_button)) },
            text = { Text(stringResource(R.string.cycle_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = { onResetCycle(); showConfirmReset = false }) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmReset = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showConfirmClear) {
        AlertDialog(
            onDismissRequest = { showConfirmClear = false },
            title = { Text(stringResource(R.string.cycle_clear_history)) },
            text = { Text(stringResource(R.string.cycle_clear_history_confirm)) },
            confirmButton = {
                TextButton(onClick = { onClearHistory(); showConfirmClear = false }) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmClear = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun CycleDayCard(state: PeriodCalendarUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.cycle_day_label),
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = if (state.currentCycleDay > 0) String.format("%02d", state.currentCycleDay) else "--",
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.cycle_phase_label),
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = state.currentPhase?.label ?: "--",
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}

@Composable
fun ActionButtons(
    onResetClick: () -> Unit,
    onSetDayClick: () -> Unit,
    onClearHistoryClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(onClick = onResetClick) {
            Icon(Icons.Default.Refresh, "Reset Cycle")
        }
        IconButton(onClick = onSetDayClick) {
            Icon(Icons.Default.SetMeal, "Set Day")
        }
        IconButton(onClick = onClearHistoryClick) {
            Icon(Icons.Default.Delete, "Clear History")
        }
    }
}

@Composable
fun CycleHistorySection(
    intervals: List<CycleInterval>,
    averageCycleLength: Double
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.history_tab),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (intervals.isEmpty()) {
                Text(
                    text = stringResource(R.string.cycle_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    intervals.forEach { interval ->
                        CycleIntervalRow(interval)
                    }
                }
                Text(
                    text = stringResource(R.string.cycle_avg_length, String.format("%.1f", averageCycleLength)),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
fun CycleIntervalRow(interval: CycleInterval) {
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = dateFormat.format(Date(interval.startTimestamp)),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = if (interval.ongoing) {
                stringResource(R.string.cycle_interval_ongoing, interval.lengthDays)
            } else {
                stringResource(R.string.cycle_interval_days, interval.lengthDays)
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (interval.ongoing) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun PhaseCard(
    phase: CyclePhase,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val phaseColor = Color(android.graphics.Color.parseColor(phase.colorHex))
                Canvas(modifier = Modifier.size(16.dp)) {
                    drawCircle(phaseColor)
                }
                Column {
                    Text(
                        text = phase.label,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Days ${phase.dayFrom}-${phase.dayTo}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Row {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, "Edit", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun NotificationSettings(
    notifyCycleStart: Boolean,
    notifyPhaseChange: Boolean,
    onToggleNotifyCycleStart: (Boolean) -> Unit,
    onToggleNotifyPhaseChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.notification_settings),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(stringResource(R.string.notification_cycle_start_title))
                    Text(
                        stringResource(R.string.notification_cycle_start_summary),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = notifyCycleStart, onCheckedChange = onToggleNotifyCycleStart)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(stringResource(R.string.notification_phase_change_title))
                    Text(
                        stringResource(R.string.notification_phase_change_summary),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = notifyPhaseChange, onCheckedChange = onToggleNotifyPhaseChange)
            }
        }
    }
}

@Composable
fun SetDayDialog(
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var dayInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cycle_set_day)) },
        text = {
            Column {
                OutlinedTextField(
                    value = dayInput,
                    onValueChange = { dayInput = it; error = null },
                    label = { Text(stringResource(R.string.cycle_set_day_hint)) },
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val day = dayInput.toIntOrNull()
                    if (day == null) {
                        error = "Please enter a valid number"
                    } else if (day < 1 || day > 99) {
                        error = "Day must be between 1 and 99"
                    } else {
                        onConfirm(day)
                    }
                }
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun PhaseConfigDialog(
    phase: CyclePhase?,
    onDismiss: () -> Unit,
    onSave: (CyclePhase) -> Unit
) {
    var label by remember { mutableStateOf(phase?.label ?: "") }
    var dayFrom by remember { mutableStateOf(phase?.dayFrom?.toString() ?: "") }
    var dayTo by remember { mutableStateOf(phase?.dayTo?.toString() ?: "") }
    var colorHex by remember { mutableStateOf(phase?.colorHex ?: "#E57373") }

    val presetColors = listOf("#E57373", "#64B5F6", "#81C784", "#FFD54F", "#BA68C8", "#4DB6AC", "#FF8A65", "#9575CD")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (phase != null) stringResource(R.string.edit_phase) else stringResource(R.string.add_phase)) },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.phase_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dayFrom,
                        onValueChange = { dayFrom = it },
                        label = { Text(stringResource(R.string.phase_day_from)) },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = dayTo,
                        onValueChange = { dayTo = it },
                        label = { Text(stringResource(R.string.phase_day_to)) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.phase_color), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                    presetColors.forEach { color ->
                        val isSelected = colorHex == color
                        Card(
                            onClick = { colorHex = color },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                val c = Color(android.graphics.Color.parseColor(color))
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    drawCircle(c)
                                }
                                if (isSelected) {
                                    Canvas(modifier = Modifier.size(12.dp)) {
                                        drawCircle(Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val from = dayFrom.toIntOrNull() ?: 1
                    val to = dayTo.toIntOrNull() ?: from
                    val clampedFrom = from.coerceIn(1, 99)
                    val clampedTo = to.coerceIn(clampedFrom, 99)
                    if (label.isNotBlank()) {
                        onSave(CyclePhase(clampedFrom, clampedTo, label, colorHex))
                    }
                }
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
