package app.aaps.plugins.source.libre2

/** Elapsed time includes device sleep; glucose quality never changes the BLE silence deadline. */
internal class Libre2SilenceWatchdog(private val clock: () -> Long, private val timeoutMillis: Long = 180_000) {
    init { require(timeoutMillis > 0) }
    var enabled: Boolean = true
    private var lastActivity: Long? = null
    fun connected() { lastActivity = clock() }
    fun packet(receivedAt: Long = clock()) { lastActivity = receivedAt }
    fun reset() { lastActivity = null }
    fun remainingMillis(): Long? {
        if (!enabled) return null
        val last = lastActivity ?: return null
        return (timeoutMillis - (clock() - last).coerceAtLeast(0)).coerceAtLeast(0)
    }
}
