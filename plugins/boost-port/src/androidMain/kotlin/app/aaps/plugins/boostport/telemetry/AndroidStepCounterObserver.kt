package app.aaps.plugins.boostport.telemetry

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener

/**
 * Android-only bridge from TYPE_STEP_COUNTER callbacks to passive KMP diagnostics.
 *
 * No SensorManager registration happens here. No Metro binding, Android service,
 * persistence, APS selection, or pump command is created by this class. A future
 * read-only observer must explicitly register/unregister this listener.
 */
internal class AndroidStepCounterObserver(
    private val diagnostics: StepCounterDiagnostics = StepCounterDiagnostics(),
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
) : SensorEventListener {

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        val raw = event.values.firstOrNull() ?: return
        if (!raw.isFinite() || raw < 0f) return
        diagnostics.accept(raw.toLong(), wallClockMs())
    }

    fun snapshot(nowMs: Long = wallClockMs()): StepCounterDiagnostics.Snapshot =
        diagnostics.snapshot(nowMs)
}
