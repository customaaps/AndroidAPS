package app.aaps.plugins.source.libre2

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.aaps.plugins.source.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun Libre2ConnectionContent(client: Libre2Client, status: Libre2Status) {
    val options by client.connectionOptions.collectAsState()
    val lastStored by client.lastStoredAt.collectAsState()
    HorizontalDivider()
    Text(stringResource(R.string.libre2_connection_settings), style = MaterialTheme.typography.titleMedium)
    ConnectionOption(stringResource(R.string.libre2_auto_recovery), options.autoRecovery) { client.setAutoRecovery(it) }
    Text(stringResource(R.string.libre2_auto_recovery_summary), style = MaterialTheme.typography.bodySmall)
    ConnectionOption(stringResource(R.string.libre2_keep_awake), options.keepAwake) { client.setKeepAwake(it) }
    Text(stringResource(R.string.libre2_keep_awake_summary), style = MaterialTheme.typography.bodySmall)

    Text(stringResource(R.string.libre2_diagnostics), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.libre2_diagnostics_session), style = MaterialTheme.typography.bodySmall)
    val notYet = stringResource(R.string.libre2_diag_not_yet)
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    fun time(value: Long) = if (value > 0) formatter.format(Date(value)) else notYet
    val d = status.diagnostics
    val stageLabel = when (d.connectionStage) {
        "WAITING" -> R.string.libre2_stage_waiting
        "CONNECTING" -> R.string.libre2_stage_connecting
        "DISCOVERING" -> R.string.libre2_stage_discovering
        "LOGIN_NOTIFY", "CHALLENGE", "PROOF", "UNLOCK" -> R.string.libre2_stage_authenticating
        "RAW_NOTIFY" -> R.string.libre2_stage_subscribing
        "STREAMING" -> R.string.libre2_stage_streaming
        else -> null
    }
    stageLabel?.let { Text(stringResource(R.string.libre2_diag_stage, stringResource(it))) }
    if (d.lastGattEvent > 0)
        Text(stringResource(R.string.libre2_diag_gatt, time(d.lastGattEvent), d.lastGattStatus))
    Text(stringResource(R.string.libre2_diag_fragment, time(d.lastFragment)))
    Text(stringResource(R.string.libre2_diag_packet, time(d.lastPacket)))
    Text(stringResource(R.string.libre2_diag_decoded, time(d.lastDecoded)))
    Text(stringResource(R.string.libre2_diag_stored, time(lastStored)))
    Text(stringResource(R.string.libre2_diag_counts, d.fragments, d.packets, d.decoded))
    if (d.decoderStatus != Int.MIN_VALUE)
        Text(stringResource(R.string.libre2_diag_decoder, d.decoderStatus, d.quality, d.sampleId))
    Text(stringResource(R.string.libre2_diag_reconnects, d.reconnects))
    if (d.lastReconnect > 0) {
        Text(stringResource(R.string.libre2_diag_reconnect_time, time(d.lastReconnect)))
        Text(stringResource(R.string.libre2_diag_reconnect_reason, when (d.reconnectReason) {
            "manual" -> stringResource(R.string.libre2_diag_manual)
            "settings" -> stringResource(R.string.libre2_diag_settings)
            else -> d.reconnectReason
        }))
    }
    if (options.keepAwake) Text(stringResource(
        if (d.wakeProtectionHeld) R.string.libre2_diag_wake_active else R.string.libre2_diag_wake_inactive))
}

@Composable
private fun ConnectionOption(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
