package app.aaps.plugins.source.libre2

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.compose.ComposablePluginContent
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.plugins.source.R

internal class Libre2Content(private val client: Libre2Client, private val context: Context) : ComposablePluginContent {
    @Composable
    override fun Render(setToolbarConfig: (ToolbarConfig) -> Unit, onNavigateBack: () -> Unit, onSettings: (() -> Unit)?) {
        val title = stringResource(R.string.source_libre2)
        SideEffect { setToolbarConfig(ToolbarConfig(title, {}, {})) }
        val status by client.status.collectAsState()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.libre2_instructions))
            Text(stringResource(status.code.textResource()))
            if (status.detail.isNotBlank()) Text(status.detail)
            Button(onClick = { client.setupIntent()?.let(context::startActivity) }, enabled = status.nativeReady) {
                Text(stringResource(R.string.libre2_scan))
            }
            OutlinedButton(onClick = { client.reconnect() }) { Text(stringResource(R.string.libre2_reconnect)) }
            Libre2ConnectionContent(client, status)
            val recent by client.recentReadings.collectAsState()
            if (recent.isNotEmpty()) {
                Text(stringResource(R.string.libre2_recent_readings))
                Text(recent.joinToString(" · ") {
                    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it.timestamp))
                })
            }
            Libre2ExchangeContent(context)
        }
    }
}

internal fun Libre2StatusCode.textResource() = when (this) {
    Libre2StatusCode.STOPPED -> R.string.libre2_stopped
    Libre2StatusCode.INITIALIZING -> R.string.libre2_initializing
    Libre2StatusCode.READY -> R.string.libre2_ready
    Libre2StatusCode.CONNECTING -> R.string.libre2_connecting
    Libre2StatusCode.CONNECTED -> R.string.libre2_connected
    Libre2StatusCode.RECEIVING -> R.string.libre2_receiving
    Libre2StatusCode.ERROR -> R.string.libre2_error
    Libre2StatusCode.PERMISSION -> R.string.libre2_permission
}
