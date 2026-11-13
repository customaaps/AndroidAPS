package app.aaps.plugins.source.libre2

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.*
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.aaps.plugins.source.R
import java.util.concurrent.atomic.AtomicBoolean

/** NFC remains enabled until the decoder finishes transceiving, including sensor activation. */
class Libre2SetupActivity : ComponentActivity() {
    private var decoder: Messenger? = null
    private var nfc: NfcAdapter? = null
    private val pending = AtomicBoolean(false)
    private var armed by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var activate = false
    private var resultText by mutableStateOf("")
    private var permitted by mutableStateOf(false)
    private var denied by mutableStateOf(false)
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permitted = granted
        denied = !granted
        if (granted) decoder?.let { runCatching { it.send(Message.obtain(null, Libre2Ipc.RECONNECT)) } }
    }
    private val responses = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.sendingUid != android.os.Process.myUid() || message.what != Libre2Ipc.SCAN_DONE) return
            pending.set(false)
            busy = false
            armed = false
            val error = message.data.getString("error")
            resultText = if (error == null) getString(R.string.libre2_scan_success) else getString(R.string.libre2_scan_failed, error)
        }
    })

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        decoder = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("decoder", Messenger::class.java)
                  else intent.getParcelableExtra("decoder")
        nfc = NfcAdapter.getDefaultAdapter(this)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.source_libre2), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.libre2_instructions))
                        if (!permitted) {
                            Text(stringResource(R.string.libre2_permission))
                            Button(onClick = { if (Build.VERSION.SDK_INT >= 31) permissionRequest.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
                                Text(stringResource(R.string.libre2_grant_permission))
                            }
                            if (denied) OutlinedButton(onClick = {
                                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName)))
                            }) { Text(stringResource(R.string.libre2_app_settings)) }
                        }
                        if (nfc?.isEnabled != true) {
                            Text(stringResource(R.string.libre2_enable_nfc))
                            OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }) { Text("NFC") }
                        }
                        Button(onClick = { arm(false) }, enabled = permitted && !busy && decoder != null && nfc?.isEnabled == true) {
                            Text(stringResource(R.string.libre2_scan_existing))
                        }
                        OutlinedButton(onClick = { arm(true) }, enabled = permitted && !busy && decoder != null && nfc?.isEnabled == true) {
                            Text(stringResource(R.string.libre2_start_new))
                        }
                        if (armed || busy) Text(stringResource(if (busy) R.string.libre2_scanning else R.string.libre2_hold_sensor))
                        if (resultText.isNotEmpty()) Text(resultText)
                        OutlinedButton(onClick = { finish() }, enabled = !busy) { Text(stringResource(R.string.libre2_done)) }
                    }
                }
            }
        }
    }

    private fun arm(start: Boolean) {
        activate = start
        armed = true
        resultText = ""
    }

    override fun onResume() {
        super.onResume()
        permitted = Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        nfc?.enableReaderMode(this, { tag ->
            runOnUiThread {
                if (!armed || !pending.compareAndSet(false, true)) return@runOnUiThread
                busy = true
                try {
                    val target = decoder ?: throw IllegalStateException("Libre2 decoder disconnected")
                    target.send(Message.obtain(null, Libre2Ipc.SCAN).apply {
                        data = Bundle().apply { putParcelable("tag", tag); putBoolean("activate", activate) }
                        replyTo = responses
                    })
                } catch (error: Exception) {
                    pending.set(false)
                    busy = false
                    armed = false
                    resultText = getString(R.string.libre2_scan_failed, error.message.orEmpty())
                }
            }
        }, NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
    }

    override fun onPause() {
        nfc?.disableReaderMode(this)
        armed = false
        super.onPause()
    }
}
