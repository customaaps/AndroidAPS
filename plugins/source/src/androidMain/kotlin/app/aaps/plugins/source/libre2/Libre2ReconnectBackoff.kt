package app.aaps.plugins.source.libre2

import kotlin.random.Random

/** Limit repeated radio work while the sensor is absent; jitter avoids fixed retry collisions. */
internal class Libre2ReconnectBackoff(private val jitterMs: () -> Long = { Random.nextLong(0, 3_001) }) {
    private var failures = 0

    fun nextDelayMillis(): Long {
        val base = minOf(60_000L, 5_000L shl failures.coerceAtMost(4))
        failures = (failures + 1).coerceAtMost(4)
        return base + jitterMs().coerceIn(0, 3_000)
    }

    fun reset() { failures = 0 }
}
