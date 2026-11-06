package app.aaps.plugins.aps.openAPSSMB

/** No accelerometer wired up on iOS yet; reads as a phone sitting still. */
actual object PhoneMovementDetector {
    actual fun phoneMoved(): Boolean = false
}
