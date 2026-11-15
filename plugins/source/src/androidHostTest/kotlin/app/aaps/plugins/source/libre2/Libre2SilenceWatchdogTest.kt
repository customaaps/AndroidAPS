package app.aaps.plugins.source.libre2

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Libre2SilenceWatchdogTest {
    @Test fun `a connection gets three minutes for its first complete packet`() {
        var elapsed = 1_000L
        val watchdog = Libre2SilenceWatchdog({ elapsed })
        assertNull(watchdog.remainingMillis())
        watchdog.connected()
        assertEquals(180_000L, watchdog.remainingMillis())
        elapsed += 179_999
        assertEquals(1L, watchdog.remainingMillis())
        elapsed++
        assertEquals(0L, watchdog.remainingMillis())
    }

    @Test fun `elapsed silence includes time while the host was asleep`() {
        var elapsed = 0L
        val watchdog = Libre2SilenceWatchdog({ elapsed })
        watchdog.connected()
        elapsed = 600_000 // No handler/CPU work happened while elapsed real time advanced.
        assertEquals(0L, watchdog.remainingMillis())
    }

    @Test fun `each complete packet resets the deadline independently of glucose quality`() {
        var elapsed = 0L
        val watchdog = Libre2SilenceWatchdog({ elapsed })
        watchdog.connected()
        repeat(20) {
            elapsed += 60_000
            watchdog.packet()
            assertEquals(180_000L, watchdog.remainingMillis())
        }
    }

    @Test fun `delayed packet processing uses its captured arrival time`() {
        var elapsed = 0L
        val watchdog = Libre2SilenceWatchdog({ elapsed })
        watchdog.connected()
        elapsed = 120_000
        watchdog.packet(receivedAt = 60_000)
        assertEquals(120_000L, watchdog.remainingMillis())
    }

    @Test fun `disabling suppresses recovery and enabling rechecks accumulated silence`() {
        var elapsed = 0L
        val watchdog = Libre2SilenceWatchdog({ elapsed })
        watchdog.connected()
        watchdog.enabled = false
        elapsed = 600_000
        assertNull(watchdog.remainingMillis())
        watchdog.enabled = true
        assertEquals(0L, watchdog.remainingMillis())
    }

    @Test fun `closing and a new connection do not inherit an expired timer`() {
        var elapsed = 0L
        val watchdog = Libre2SilenceWatchdog({ elapsed })
        watchdog.connected()
        elapsed = 600_000
        watchdog.reset()
        assertNull(watchdog.remainingMillis())
        watchdog.connected()
        assertEquals(180_000L, watchdog.remainingMillis())
    }
}
