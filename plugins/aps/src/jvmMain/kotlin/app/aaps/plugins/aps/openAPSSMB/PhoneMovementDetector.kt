package app.aaps.plugins.aps.openAPSSMB

/** No accelerometer on desktop; reads as a phone sitting still. */
actual object PhoneMovementDetector {
    actual fun phoneMoved(): Boolean = false
}
