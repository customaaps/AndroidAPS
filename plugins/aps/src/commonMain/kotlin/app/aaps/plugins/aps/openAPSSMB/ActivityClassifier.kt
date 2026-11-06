package app.aaps.plugins.aps.openAPSSMB

import kotlin.concurrent.Volatile

object ActivityClassifier {

    // Values match DetectedActivity constants from com.google.android.gms.location
    const val PHONE_BICYCLE = 1
    const val PHONE_STILL   = 3
    const val PHONE_WALKING = 7
    const val PHONE_RUNNING = 8

    @Volatile var phoneActivity: Int = PHONE_STILL

    fun classify(
        steps15m: Int,
        avgHrLast15m: Double,
        phoneActivity: Int = this.phoneActivity,
        restingHr: Int = 60
    ): ActivityClass {
        // Rule 1: cycling — few steps but OS knows for sure
        if (phoneActivity == PHONE_BICYCLE) return ActivityClass.AEROBIC
        // Rule 2: explicit OS signal
        if (phoneActivity == PHONE_WALKING || phoneActivity == PHONE_RUNNING) return ActivityClass.AEROBIC
        // Rule 3: ~10 steps/min = slow walk (fallback from steps)
        if (steps15m > 150) return ActivityClass.AEROBIC
        // Rule 4: minimal movement + elevated HR = gym/strength training
        if (steps15m < 30 && avgHrLast15m > 0.0 && avgHrLast15m > restingHr * 1.25) return ActivityClass.RESISTANCE
        return ActivityClass.REST
    }
}
