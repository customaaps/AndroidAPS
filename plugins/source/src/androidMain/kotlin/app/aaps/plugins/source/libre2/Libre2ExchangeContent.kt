package app.aaps.plugins.source.libre2

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.aaps.plugins.source.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
internal fun Libre2ExchangeContent(context: Context, libreViewOnly: Boolean = false) {
    val exchange = remember(context) { Libre2Exchange(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf<Libre2ExchangeSettings?>(null) }
    var error by remember { mutableStateOf(false) }
    OutlinedButton(onClick = {
        error = false
        scope.launch {
            try { settings = withContext(Dispatchers.IO) { exchange.settings() }; show = true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = true }
        }
    }) { Text(stringResource(if (libreViewOnly) R.string.libre2_libreview_export else R.string.libre2_exchange)) }
    if (error) Text(stringResource(R.string.libre2_exchange_storage_error))
    if (show && settings != null) {
        val initial = requireNotNull(settings)
        var xdrip by remember { mutableStateOf(initial.xdrip) }
        var email by remember { mutableStateOf(initial.email) }
        // Credentials are intentionally not rememberSaveable (never written into saved instance state).
        var password by remember { mutableStateOf(initial.password) }
        var saving by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf(initial) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(5_000)
                try { status = withContext(Dispatchers.IO) { exchange.settings() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = true }
            }
        }
        AlertDialog(
            onDismissRequest = { if (!saving) show = false },
            title = { Text(stringResource(if (libreViewOnly) R.string.libre2_libreview_export else R.string.libre2_exchange)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!libreViewOnly) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.libre2_xdrip_export), modifier = Modifier.weight(1f))
                            Switch(checked = xdrip, onCheckedChange = { xdrip = it }, enabled = !saving)
                        }
                        Text(stringResource(R.string.libre2_xdrip_instructions))
                    }
                    if (libreViewOnly) {
                        Text(stringResource(R.string.libre2_libreview_config_toggle))
                        Text(stringResource(R.string.libre2_libreview_region))
                        OutlinedTextField(value = email, onValueChange = { email = it }, enabled = !saving,
                            label = { Text(stringResource(R.string.libre2_libreview_email)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                        OutlinedTextField(value = password, onValueChange = { password = it }, enabled = !saving,
                            label = { Text(stringResource(R.string.libre2_libreview_password)) }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                        Text(stringResource(R.string.libre2_libreview_credentials_note))
                        if (status.accountId.isNotEmpty()) Text(stringResource(R.string.libre2_libreview_account, status.accountId))
                        val statusText = when (status.status) {
                            "waiting_sensor" -> R.string.libre2_libreview_waiting_sensor
                            "waiting_readings" -> R.string.libre2_libreview_waiting_readings
                            "credentials" -> R.string.libre2_libreview_credentials_required
                            "authenticated" -> R.string.libre2_libreview_authenticated
                            "uploaded" -> R.string.libre2_libreview_uploaded
                            "uploaded_current" -> R.string.libre2_libreview_uploaded_current
                            "uploaded_history" -> R.string.libre2_libreview_uploaded_history
                            "auth" -> R.string.libre2_libreview_auth_error
                            "rate_limited" -> R.string.libre2_libreview_rate_limit
                            "" -> R.string.libre2_libreview_not_checked
                            else -> R.string.libre2_libreview_network_error
                        }
                        Text(stringResource(statusText))
                        if (status.lastUpload > 0) Text(DateFormat.getDateTimeInstance().format(Date(status.lastUpload)))
                        if (status.lastCurrentCount >= 0)
                            Text(stringResource(R.string.libre2_libreview_accepted_counts, status.lastCurrentCount, status.lastHistoryCount))
                        if (status.lastHistoryUpload > 0)
                            Text(stringResource(R.string.libre2_libreview_history_time,
                                DateFormat.getDateTimeInstance().format(Date(status.lastHistoryUpload))))
                        if (status.lastAttemptAt > 0) {
                            Text(stringResource(R.string.libre2_libreview_attempt_counts,
                                DateFormat.getDateTimeInstance().format(Date(status.lastAttemptAt)),
                                status.lastAttemptCurrent, status.lastAttemptHistory))
                            Text(stringResource(R.string.libre2_libreview_attempt_result, when (status.lastUploadResult) {
                                "accepted" -> stringResource(R.string.libre2_libreview_result_accepted)
                                "sending" -> stringResource(R.string.libre2_libreview_result_sending)
                                else -> status.lastUploadResult
                            }))
                        }
                    }
                    if (error) Text(stringResource(R.string.libre2_exchange_storage_error))
                }
            },
            confirmButton = {
                TextButton(enabled = !saving && (!libreViewOnly || email.isNotBlank() && password.isNotEmpty()), onClick = {
                    saving = true
                    error = false
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                if (libreViewOnly) exchange.saveLibreViewCredentials(email, password)
                                else exchange.saveXdrip(xdrip)
                            }
                            status = withContext(Dispatchers.IO) { exchange.settings() }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = true }
                        finally { saving = false }
                    }
                }) { Text(stringResource(R.string.libre2_exchange_save)) }
            },
            dismissButton = {
                TextButton(onClick = { show = false }, enabled = !saving) { Text(stringResource(R.string.libre2_exchange_close)) }
            }
        )
    }
}
