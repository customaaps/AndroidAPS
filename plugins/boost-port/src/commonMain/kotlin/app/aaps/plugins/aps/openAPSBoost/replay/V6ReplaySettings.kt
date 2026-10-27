package app.aaps.plugins.aps.openAPSBoost.replay

/** Recorded source settings; no opt-in feature is enabled by default. */
internal data class V6ReplaySettings(
    val confirmTrancheEnabled: Boolean = false,
    val immediateFraction: Double = 0.5,
    val releaseThreshold: Double = 0.48,
)
