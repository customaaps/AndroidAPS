package app.aaps.plugins.boostport.aps

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import app.aaps.core.interfaces.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Explicit settings action only. No permission dialog is launched by calculations or workers. */
class BoostDataPermissionsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null
    private val readPermissions = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class)
    )

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        title = getString(R.string.boost_permissions_title)
    }

    override fun onResume() {
        super.onResume()
        showPermissions()
    }

    private fun phonePermissionGranted() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun grantLabel(granted: Boolean) = getString(
        if (granted) R.string.boost_permission_granted else R.string.boost_permission_not_granted
    )

    private fun showPermissions() {
        refreshJob?.cancel()
        val padding = (20 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        fun text(value: String) = TextView(this).apply {
            text = value
            textSize = 18f
            setPadding(0, padding / 2, 0, padding / 2)
            content.addView(this)
        }
        fun button(label: Int, action: () -> Unit) {
            content.addView(Button(this).apply {
                setText(label)
                setOnClickListener { action() }
            })
        }
        text(getString(R.string.boost_permissions_title)).textSize = 24f
        text(getString(R.string.boost_permissions_rationale))
        text(getString(R.string.boost_permission_phone_status, grantLabel(phonePermissionGranted())))
        if (!phonePermissionGranted()) {
            button(R.string.boost_permission_allow_phone) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), PHONE_REQUEST)
            }
        }
        // Also works after a permanent denial, when Android no longer shows its request dialog.
        button(R.string.boost_permission_app_settings) {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> {
                val status = text(getString(R.string.boost_permission_checking))
                button(R.string.boost_permission_allow_health) {
                    try {
                        val contract = PermissionController.createRequestPermissionResultContract()
                        startActivityForResult(contract.createIntent(this, readPermissions), HEALTH_REQUEST)
                    } catch (_: ActivityNotFoundException) {
                        showOpenError()
                    } catch (_: SecurityException) {
                        showOpenError()
                    }
                }
                button(R.string.boost_permission_health_settings) {
                    open(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
                }
                refreshJob = scope.launch {
                    try {
                        val granted = HealthConnectClient.getOrCreate(this@BoostDataPermissionsActivity)
                            .permissionController.getGrantedPermissions()
                        status.text = getString(R.string.boost_permission_health_status,
                            grantLabel(HealthPermission.getReadPermission(StepsRecord::class) in granted),
                            grantLabel(HealthPermission.getReadPermission(HeartRateRecord::class) in granted))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        status.setText(R.string.boost_permission_check_failed)
                    }
                }
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                text(getString(R.string.boost_permission_health_install_needed))
                button(R.string.boost_permission_install_health) {
                    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$HEALTH_PROVIDER"))
                    try {
                        startActivity(market)
                    } catch (_: ActivityNotFoundException) {
                        open(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$HEALTH_PROVIDER")))
                    }
                }
            }
            else -> text(getString(R.string.boost_permission_health_unavailable))
        }
        button(R.string.boost_permission_done) { finish() }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            showOpenError()
        } catch (_: SecurityException) {
            showOpenError()
        }
    }

    private fun showOpenError() {
        Toast.makeText(this, R.string.boost_permission_open_failed, Toast.LENGTH_LONG).show()
    }

    override fun onRequestPermissionsResult(request: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(request, permissions, grants)
        if (request == PHONE_REQUEST) showPermissions()
    }

    override fun onActivityResult(request: Int, result: Int, intent: Intent?) {
        super.onActivityResult(request, result, intent)
        if (request == HEALTH_REQUEST) showPermissions()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val PHONE_REQUEST = 1
        const val HEALTH_REQUEST = 2
        const val HEALTH_PROVIDER = "com.google.android.apps.healthdata"
    }
}

/** Required Health Connect rationale entry; displays purpose, never starts a request. */
class BoostHealthRationaleActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(TextView(this).apply {
            text = getString(R.string.boost_permissions_rationale)
            textSize = 18f
            setPadding(32, 48, 32, 32)
        })
    }
}
