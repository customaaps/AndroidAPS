package app.aaps.plugins.aps.openAPSSMB

/**
 * Whether the phone has moved recently, fed to AutoISF's activity-detection inputs. Backed by the
 * accelerometer on Android; platforms with no such sensor report no movement, which is the same
 * reading AutoISF would see from a phone sitting still.
 */
expect object PhoneMovementDetector {
    fun phoneMoved(): Boolean
}
