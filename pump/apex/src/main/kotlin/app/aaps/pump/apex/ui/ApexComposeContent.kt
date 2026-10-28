package app.aaps.pump.apex.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.ui.compose.ComposablePluginContent
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.pump.apex.ApexPumpPlugin
import app.aaps.pump.apex.R
import app.aaps.pump.apex.events.EventApexPumpDataChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Created lazily by PluginDescription; composition starts only when the host calls Render. */
class ApexComposeContent(private val plugin: ApexPumpPlugin) : ComposablePluginContent {
    @Composable
    override fun Render(
        setToolbarConfig: (ToolbarConfig) -> Unit,
        onNavigateBack: () -> Unit,
        onSettings: (() -> Unit)?
    ) {
        ApexOverviewScreen(plugin)
    }
}

@Composable
private fun ApexOverviewScreen(plugin: ApexPumpPlugin) {
    var revision by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val hideSerial by plugin.hideSerial.collectAsState()
    LaunchedEffect(plugin) {
        launch { plugin.rxBus.toFlow(EventApexPumpDataChanged::class).collect { revision++ } }
        while (isActive) {
            delay(15_000)
            revision++
        }
    }
    val pump = plugin.pump
    val status = remember(revision) { pump.status }
    val action = remember(revision) { plugin.driverStatus.message }
    val bolusing = remember(revision) { pump.inProgressBolus != null }
    val connection = remember(revision) {
        plugin.rh.gs(when {
            plugin.isConnected() -> R.string.overview_connection_status_connected
            plugin.isConnecting() -> R.string.overview_connection_status_connecting
            else -> R.string.overview_connection_status_disconnected
        })
    }
    val rh = plugin.rh
    val concentration = plugin.concentrationHelper
    @Composable fun entry(label: Int, value: String) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(rh.gs(label), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card {
            Column(Modifier.padding(16.dp)) {
                entry(R.string.status_conn_status, connection)
                if (!hideSerial) entry(R.string.setting_serial_number, pump.serialNumber)
                entry(R.string.status_pump_status, status?.getPumpStatus(rh) ?: "—")
                entry(R.string.status_battery, status?.getBatteryLevel(rh) ?: "—")
                entry(R.string.status_reservoir, if (status == null) "—" else concentration.insulinAmountString(PumpInsulin(pump.reservoirLevel)))
                entry(R.string.status_today_delivered, status?.let { concentration.insulinAmountString(PumpInsulin(it.todayDeliveredUnits)) } ?: "—")
                entry(R.string.status_basal, pump.basal?.let { concentration.basalRateString(PumpRate(it.rate), true) } ?: "—")
                entry(R.string.status_tbr, pump.tbr?.let {
                    val value = if (it.isAbsolute) it.rate ?: 0.0 else (it.percentage ?: 100).toDouble()
                    concentration.basalRateString(PumpRate(value), it.isAbsolute) +
                        " · " + rh.gs(R.string.apex_remaining_minutes, (it.durationMinutes - it.elapsedMinutes).coerceAtLeast(0))
                } ?: "—")
                entry(R.string.status_last_bolus, pump.lastBolus?.let {
                    concentration.insulinAmountString(PumpInsulin((it.standardPerformed + it.extendedPerformed) * 0.025)) + " · " + plugin.dateUtil.timeString(it.dateTime.millis)
                } ?: "—")
                entry(R.string.apex_last_contact, if (pump.lastStatusReceivedAt == 0L) "—" else plugin.dateUtil.dateAndTimeAndSecondsString(pump.lastStatusReceivedAt))
            }
        }
        action?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Button(enabled = !refreshing && plugin.isConfigured(), onClick = {
            refreshing = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { plugin.commandQueue.readStatus("Apex manual refresh") }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    plugin.fabricPrivacy.logException(error)
                } finally {
                    refreshing = false
                    revision++
                }
            }
        }) { Text(rh.gs(R.string.apex_refresh)) }
        if (bolusing) Button(onClick = {
            plugin.userEntryLogger.log(Action.CANCEL_BOLUS, Sources.Pump)
            plugin.commandQueue.cancelAllBoluses(null)
        }) { Text(rh.gs(R.string.cancel_bolus)) }
    }
}
