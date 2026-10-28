package app.aaps.plugins.boostport.aps

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import app.aaps.core.interfaces.R
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord

/** Explicit settings action only. No permission dialog is launched by calculations or workers. */
class BoostDataPermissionsActivity : Activity() {
    private fun health() {
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) { finish(); return }
        val contract = PermissionController.createRequestPermissionResultContract()
        startActivityForResult(contract.createIntent(this,
            setOf(HealthPermission.getReadPermission(HeartRateRecord::class))), 2)
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (state != null) return
        if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 1)
        else health()
    }
    override fun onRequestPermissionsResult(request: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(request, permissions, grants)
        if (request == 1) health()
    }
    override fun onActivityResult(request: Int, result: Int, intent: Intent?) {
        super.onActivityResult(request, result, intent)
        if (request == 2) finish()
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
