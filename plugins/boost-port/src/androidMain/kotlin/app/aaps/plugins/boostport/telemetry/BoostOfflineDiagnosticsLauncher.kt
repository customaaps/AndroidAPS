package app.aaps.plugins.boostport.telemetry

import android.content.Context
import android.content.Intent

/**
 * Internal-only Android entry point for the passive diagnostics screen.
 * Does not register a launch tile, worker, receiver or APS plugin.
 */
internal object BoostOfflineDiagnosticsLauncher {
    fun intent(context: Context, report: BoostV1V5OfflineIntegration.Report? = null): Intent {
        val display = BoostPortReadOnlyDisplay.project(report)
        val status = when (display.status) {
            BoostPortReadOnlyDisplay.Status.NO_DATA -> BoostOfflineDiagnosticsStatus.NO_DATA
            BoostPortReadOnlyDisplay.Status.INVALID_DIAGNOSTICS -> BoostOfflineDiagnosticsStatus.INVALID
            BoostPortReadOnlyDisplay.Status.REVIEW_REQUIRED -> BoostOfflineDiagnosticsStatus.REVIEW
            BoostPortReadOnlyDisplay.Status.DIAGNOSTICS_AVAILABLE -> BoostOfflineDiagnosticsStatus.AVAILABLE
        }
        return Intent(context, BoostOfflineDiagnosticsActivity::class.java).apply {
            putExtra(BoostOfflineDiagnosticsActivity.EXTRA_STATUS, status.name)
        }
    }
}
