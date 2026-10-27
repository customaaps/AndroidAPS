package app.aaps.plugins.boostport.telemetry

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

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
        add("Boost — offline diagnostics", 22f)
        add("Boost is not controlling insulin. Dmitry's active APS remains unchanged.", 16f)
        add("Mode: read-only / no treatment decisions", 16f)
        val raw = intent.getStringExtra(EXTRA_STATUS)
        val status = BoostOfflineDiagnosticsStatus.fromWire(raw)
        add(status.displayText, 16f)
        add("No sensor, meal or insulin data is acquired by this screen.", 14f)
        view.addView(column)
        setContentView(view)
    }

    companion object {
        const val EXTRA_STATUS = "app.aaps.plugins.boostport.EXTRA_DIAGNOSTIC_STATUS"
    }
}
