package app.aaps.plugins.boostport.telemetry

/**
 * Non-actuating, pure KMP diagnostic adapter of the legacy Boost HR source resolver.
 * Does not query Health Connect, register an APS plugin, or influence insulin dosing.
 * Future timestamps are treated as not fresh to avoid clock-skew false positives.
 */
internal object HrSourceResolver {
    private const val GARMIN = "garmin"
    private const val HC = "hc"
    private const val WORN_PREFIX = "worn:"
    private const val FRESH_MS = 12 * 60_000L

    data class Reading(val device: String, val timestampMs: Long)
    data class SourceState(val source: String, val count: Int, val ageMs: Long) {
        val fresh: Boolean get() = ageMs in 0L..FRESH_MS
    }
    data class Resolution(
        val active: String?,
        val anyFresh: Boolean,
        val note: String,
        val states: List<SourceState>,
    )

    private fun canonical(device: String): String {
        val tag = device.trim()
        return when {
            tag.contains("garmin", ignoreCase = true) -> GARMIN
            tag.equals("HealthConnect", ignoreCase = true) || tag.isBlank() -> HC
            else -> WORN_PREFIX + tag
        }
    }

    private fun tier(source: String): Int = when {
        source == GARMIN || source.startsWith(WORN_PREFIX) -> 0
        source == HC -> 1
        else -> 2
    }

    fun resolve(readings: List<Reading>, nowMs: Long): Resolution {
        if (readings.isEmpty()) return Resolution(null, false, "none", emptyList())
        val states = readings.groupBy { canonical(it.device) }
            .map { (source, rows) ->
                SourceState(source, rows.size, nowMs - rows.maxOf { it.timestampMs })
            }.sortedWith(compareBy({ tier(it.source) }, { it.ageMs }))
        val active = states.filter { it.fresh }
            .minWithOrNull(compareBy({ tier(it.source) }, { it.ageMs }))
        val note = states.joinToString(" ") {
            "${it.source}(${if (it.fresh) "f" else "-"},${it.count},${it.ageMs / 60_000L}m)"
        }
        return Resolution(active?.source, active != null, note, states)
    }
}
