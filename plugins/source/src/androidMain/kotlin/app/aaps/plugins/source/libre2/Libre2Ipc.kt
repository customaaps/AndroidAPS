package app.aaps.plugins.source.libre2

internal object Libre2Ipc {
    const val REGISTER = 1
    const val SCAN = 2
    const val RECONNECT = 3
    const val STOP = 4
    const val STATUS = 10
    const val READING = 11
    const val SCAN_DONE = 12
    const val PROCESS_SUFFIX = ":libre2"
}
internal enum class Libre2StatusCode { STOPPED, INITIALIZING, READY, CONNECTING, CONNECTED, RECEIVING, ERROR, PERMISSION }
internal data class Libre2Status(
    val code: Libre2StatusCode = Libre2StatusCode.STOPPED,
    val nativeReady: Boolean = false,
    val detail: String = "",
    val lastReadingMillis: Long = 0,
)
