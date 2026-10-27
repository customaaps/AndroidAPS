package app.aaps.plugins.boostport.telemetry

/**
 * Pure, read-only KMP diagnostics for step-source selection.
 * Adapted from legacy StepSourceResolver. No Android sensors, storage, APS,
 * or pump integration. Unknown / stale data cannot establish a live feed.
 */
internal object StepSourceResolver {
    private const val WEAR = "wear"
    private const val GARMIN = "garmin"
    private const val PHONE = "phone"
    private const val HC_PREFIX = "hc:"
    private const val GARMIN_PKG = "com.garmin.android.apps.connectmobile"

    data class SourceState(
        val source: String,
        val fresh: Boolean,
        val coverageDays: Int,
        val stepsToday: Int,
    )

    data class Resolution(
        val active: String?,
        val stepsToday: Int,
        val activeFresh: Boolean,
        val note: String,
    )

    private fun canonical(raw: String): String = when {
        raw == WEAR || raw == PHONE -> raw
        raw == GARMIN || raw == GARMIN_PKG -> GARMIN
        raw.startsWith(HC_PREFIX) -> raw
        else -> HC_PREFIX + raw.substringAfterLast('.')
    }

    private fun tier(source: String): Int = when {
        source == WEAR -> 0
        source == GARMIN -> 1
        source.startsWith(HC_PREFIX) -> 2
        source == PHONE -> 3
        else -> 4
    }

    fun resolve(states: List<SourceState>): Resolution {
        val ranked = states.map { it.copy(source = canonical(it.source)) }
            .filter { it.coverageDays >= 0 && it.stepsToday >= 0 }
            .sortedBy { tier(it.source) }
        val chosen = ranked.firstOrNull { it.fresh }
            ?: ranked.firstOrNull { it.coverageDays > 0 || it.stepsToday > 0 }
        val note = ranked.joinToString(" ") {
            "${it.source}(${if (it.fresh) "f" else "-"},${it.coverageDays}d)"
        }.ifEmpty { "none" }
        return Resolution(
            active = chosen?.source,
            stepsToday = chosen?.stepsToday ?: 0,
            activeFresh = chosen?.fresh ?: false,
            note = if (chosen == null) "none[$note]" else note,
        )
    }
}
