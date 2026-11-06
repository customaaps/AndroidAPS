package app.aaps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import app.aaps.plugins.aps.openAPSSMB.ActivityClassifier

class ActivityRecognitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        for (event in result.transitionEvents) {
            if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                ActivityClassifier.phoneActivity = event.activityType
            } else if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT
                && event.activityType != DetectedActivity.STILL) {
                ActivityClassifier.phoneActivity = DetectedActivity.STILL
            }
        }
    }
}
