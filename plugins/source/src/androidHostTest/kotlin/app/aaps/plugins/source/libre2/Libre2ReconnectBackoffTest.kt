package app.aaps.plugins.source.libre2

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Libre2ReconnectBackoffTest {
    @Test
    fun `missing sensor backs off without an unbounded counter or a fast retry loop`() {
        val backoff = Libre2ReconnectBackoff { 0 }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L),
            List(5) { backoff.nextDelayMillis() })
        repeat(10_000) { assertEquals(60_000L, backoff.nextDelayMillis()) }
        backoff.reset()
        assertEquals(5_000L, backoff.nextDelayMillis())
    }

    @Test
    fun `jitter spreads connection attempts and stays bounded`() {
        val jitter = ArrayDeque(listOf(0L, 1_000L, 3_000L, -1L, 9_000L))
        val backoff = Libre2ReconnectBackoff { jitter.removeFirst() }
        assertEquals(listOf(5_000L, 11_000L, 23_000L, 40_000L, 63_000L),
            List(5) { backoff.nextDelayMillis() })
    }
}
