package app.aaps.plugins.source.libre2

import android.content.Context
import android.os.Handler
import android.os.PowerManager

/** Opt-in CPU protection. A bounded lease expires even if the service worker stops processing work. */
internal class Libre2WakeProtection(
    context: Context,
    private val handler: Handler,
    private val onFailure: () -> Unit,
) {
    private val power = context.getSystemService(PowerManager::class.java)
    private var lock: PowerManager.WakeLock? = null
    private var enabled = false
    private val renew = object : Runnable {
        override fun run() = synchronized(this@Libre2WakeProtection) {
            if (enabled) acquireLease()
        }
    }

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (!value) { close(); return }
        if (enabled && isHeld()) return
        enabled = true
        acquireLease()
    }

    private fun acquireLease() {
        handler.removeCallbacks(renew)
        try {
            val lease = lock ?: power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AAPS:Libre2Connection")
                .apply { setReferenceCounted(false) }.also { lock = it }
            lease.acquire(LEASE_MILLIS)
            if (!handler.postDelayed(renew, RENEW_MILLIS)) { close(); onFailure() }
        } catch (_: Exception) {
            enabled = false
            lock?.let { if (it.isHeld) runCatching { it.release() } }
            onFailure()
        }
    }

    @Synchronized fun isHeld() = lock?.isHeld == true

    @Synchronized
    fun close() {
        enabled = false
        handler.removeCallbacks(renew)
        lock?.let { if (it.isHeld) runCatching { it.release() } }
    }

    companion object {
        internal const val LEASE_MILLIS = 5 * 60_000L
        internal const val RENEW_MILLIS = 60_000L
    }
}
