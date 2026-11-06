package app.aaps

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSSMB.StepService
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Registers the step-counter sensor and the OS activity-transition updates that feed AutoISF/SMB's
 * ActivityClassifier ([StepService], [ActivityRecognitionReceiver]) - including the existing
 * "steps_graph_from_smartphone" preference (BooleanKey.ActivityMonitorShowStepsFromSmartphone),
 * which is meant to let a user without a paired watch feed step data from the phone instead. Neither
 * worked because the sensor was never actually registered.
 *
 * Both require `ACTIVITY_RECOGNITION` on Android 10+. The app only asks for it through the
 * Permissions sheet, which the user can act on at any point in the app's lifetime - not only at
 * process start - so this is re-tried on every resume ([ComposeMainActivity.refreshOnResume])
 * rather than once in [MainApp.doInit], which would otherwise leave the sensors unregistered until
 * the process happens to restart after the grant.
 */
@Inject
@SingleIn(AppScope::class)
class ActivityMonitoringRegistrar(
    private val aapsLogger: AAPSLogger
) {

    @Volatile private var registered = false

    @Synchronized
    fun registerIfPermitted(context: Context) {
        if (registered) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) return

        val stepCounterRegistered = registerStepCounter(context)
        val transitionsRequested = registerActivityTransitions(context)
        if (stepCounterRegistered && transitionsRequested) registered = true
    }

    private fun registerStepCounter(context: Context): Boolean {
        try {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if (stepSensor != null) {
                sensorManager.registerListener(StepService, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
                aapsLogger.debug(LTag.CORE, "Step counter sensor registered")
                return true
            }
            aapsLogger.warn(LTag.CORE, "Step counter sensor not available on this device")
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Failed to register step counter sensor", e)
        }
        return false
    }

    private fun registerActivityTransitions(context: Context): Boolean {
        try {
            val activityIntent = Intent(context, ActivityRecognitionReceiver::class.java)
            val activityPendingIntent = PendingIntent.getBroadcast(
                context, 1, activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            val transitions = listOf(
                ActivityTransition.Builder().setActivityType(DetectedActivity.WALKING).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build(),
                ActivityTransition.Builder().setActivityType(DetectedActivity.WALKING).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build(),
                ActivityTransition.Builder().setActivityType(DetectedActivity.RUNNING).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build(),
                ActivityTransition.Builder().setActivityType(DetectedActivity.RUNNING).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build(),
                ActivityTransition.Builder().setActivityType(DetectedActivity.ON_BICYCLE).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build(),
                ActivityTransition.Builder().setActivityType(DetectedActivity.ON_BICYCLE).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build(),
                ActivityTransition.Builder().setActivityType(DetectedActivity.STILL).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build(),
            )
            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), activityPendingIntent)
                .addOnSuccessListener { aapsLogger.debug(LTag.CORE, "Activity recognition transitions registered") }
                .addOnFailureListener { e ->
                    registered = false
                    aapsLogger.warn(LTag.CORE, "Activity recognition registration failed: ${e.message}")
                }
            return true
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Failed to register activity recognition", e)
        }
        return false
    }
}
