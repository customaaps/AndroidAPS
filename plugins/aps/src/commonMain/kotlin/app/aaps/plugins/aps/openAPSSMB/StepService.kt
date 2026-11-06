package app.aaps.plugins.aps.openAPSSMB

/**
 * Recent step counts, bucketed into 5-minute windows, fed to AutoISF's activity-detection inputs.
 * Backed by a real step sensor on Android; platforms with no such sensor report zero steps, which
 * is the same reading AutoISF would see from a stationary phone.
 */
expect object StepService {
    fun getRecentStepCount5Min(): Int
    fun getRecentStepCount10Min(): Int
    fun getRecentStepCount15Min(): Int
    fun getRecentStepCount30Min(): Int
    fun getRecentStepCount60Min(): Int
    fun getRecentStepCount180Min(): Int
}
