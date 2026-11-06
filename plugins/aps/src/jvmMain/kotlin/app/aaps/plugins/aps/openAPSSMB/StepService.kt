package app.aaps.plugins.aps.openAPSSMB

/** No step sensor on desktop; reads as a stationary phone. */
actual object StepService {
    actual fun getRecentStepCount5Min(): Int = 0
    actual fun getRecentStepCount10Min(): Int = 0
    actual fun getRecentStepCount15Min(): Int = 0
    actual fun getRecentStepCount30Min(): Int = 0
    actual fun getRecentStepCount60Min(): Int = 0
    actual fun getRecentStepCount180Min(): Int = 0
}
