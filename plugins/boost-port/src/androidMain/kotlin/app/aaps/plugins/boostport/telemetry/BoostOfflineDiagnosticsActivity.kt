package app.aaps.plugins.boostport.telemetry

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.aaps.core.interfaces.R

/**
 * Explicitly launched internal screen. No background subscription or pump APIs.
 * Callers can open it using BoostOfflineDiagnosticsLauncher.intent(context).
 */
class BoostOfflineDiagnosticsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val space = (20 * resources.displayMetrics.density).toInt()
            setPadding(space, space, space, space)
        }
        fun add(message: String, size: Float) {
            column.addView(TextView(this).apply {
                text = message
                textSize = size
                gravity = Gravity.START
                val vertical = (10 * resources.displayMetrics.density).toInt()
                setPadding(0, vertical, 0, vertical)
            })
        }
        add(getString(R.string.boost_offline_diagnostics_title), 22f)
        add(getString(R.string.boost_offline_diagnostics_no_control), 16f)
        add(getString(R.string.boost_offline_diagnostics_read_only), 16f)
        val raw = intent.getStringExtra(EXTRA_STATUS)
        val status = BoostOfflineDiagnosticsStatus.fromWire(raw)
        add(getString(when (status) {
            BoostOfflineDiagnosticsStatus.NO_DATA -> R.string.boost_offline_diagnostics_no_data
            BoostOfflineDiagnosticsStatus.INVALID -> R.string.boost_offline_diagnostics_invalid
            BoostOfflineDiagnosticsStatus.REVIEW -> R.string.boost_offline_diagnostics_review
            BoostOfflineDiagnosticsStatus.AVAILABLE -> R.string.boost_offline_diagnostics_available
        }), 16f)
        add(getString(R.string.boost_offline_diagnostics_no_acquisition), 14f)
        view.addView(column)
        setContentView(view)
    }

    companion object {
        const val EXTRA_STATUS = "app.aaps.plugins.boostport.EXTRA_DIAGNOSTIC_STATUS"
    }
}
