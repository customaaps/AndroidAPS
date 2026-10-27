package app.aaps.plugins.boostport.telemetry

/**
 * Inert KMP port of BoostMlFeatureBuilder's v12 ring-buffer and feature-order
 * rules. Diagnostic comparison only: no model invocation, scheduling, APS,
 * profile changes, persistence, pump commands, or insulin recommendations.
 *
 * The source model was trained with five-minute lags; ordinary one-minute
 * samples replace the last slot rather than shortening its time horizon.
 */
internal object BoostMlFeatureDiagnostics {
    const val LOOKBACK = 6
    const val STALE_AFTER_MS = 35L * 60_000L
    const val LAG_SPACING_MS = 5L * 60_000L
    private const val LAG_TOLERANCE_MS = 30_000L

    val LOOKBACK_FEATURES = listOf(
        "cgm_mgdl", "iob_iob", "iob_activity",
        "sug_eventualBG", "recent_smb_units_60m", "sug_minDelta",
    )

    data class Snapshot(
        val timestampMs: Long,
        val cgmMgdl: Double,
        val iobIob: Double,
        val iobActivity: Double,
        val eventualBg: Double,
        val recentSmbUnits60m: Double,
        val minDelta: Double,
    ) {
        fun valueOf(feature: String): Double? = when (feature) {
            "cgm_mgdl" -> cgmMgdl
            "iob_iob" -> iobIob
            "iob_activity" -> iobActivity
            "sug_eventualBG" -> eventualBg
            "recent_smb_units_60m" -> recentSmbUnits60m
            "sug_minDelta" -> minDelta
            else -> null
        }
    }

    /** Immutable history copy: no component can modify the ring behind a caller. */
    class Ring private constructor(private val items: List<Snapshot>) {
        constructor() : this(emptyList())

        val size: Int get() = items.size
        fun snapshots(): List<Snapshot> = items.toList()
        fun lagged(lag: Int): Snapshot? =
            if (lag in 0 until LOOKBACK && lag < items.size) items[items.lastIndex - lag] else null

        /**
         * Mirrors the legacy 4m30s replacement threshold, six-slot cap and
         * 35-minute staleness cutoff. Unlike the original, an out-of-order
         * timestamp clears the history, rather than forging a backwards lag.
         */
        fun accept(snapshot: Snapshot): Ring {
            if (snapshot.timestampMs < 0L) return Ring()
            val last = items.lastOrNull()
            if (last != null && snapshot.timestampMs < last.timestampMs) return Ring()
            val next = if (last != null &&
                snapshot.timestampMs - last.timestampMs < LAG_SPACING_MS - LAG_TOLERANCE_MS
            ) {
                items.dropLast(1) + snapshot
            } else {
                items + snapshot
            }
            val cutoff = (snapshot.timestampMs - STALE_AFTER_MS).coerceAtLeast(0L)
            return Ring(next.filter { it.timestampMs >= cutoff }.takeLast(LOOKBACK))
        }
    }

    /**
     * Reconstruct a feature vector strictly in model-declared order for
     * offline parity inspection. Null means mismatched/invalid history, never
     * a safe value for an active insulin decision.
     *
     * Legacy: lagN falls back to current when the ring is cold, and missing
     * static features / nonfinite values become zero. The new guard rejects
     * unknown lag names and a ring not ending at the supplied current cycle.
     */
    fun build(
        featureNames: List<String>,
        current: Snapshot,
        ring: Ring,
        staticValues: Map<String, Double>,
    ): DoubleArray? {
        if (current.timestampMs < 0L ||
            ring.lagged(0)?.timestampMs != current.timestampMs ||
            featureNames.isEmpty() ||
            featureNames.size != featureNames.distinct().size
        ) return null

        val out = DoubleArray(featureNames.size)
        for ((index, name) in featureNames.withIndex()) {
            val marker = name.indexOf("_lag")
            val candidate = if (marker > 0) {
                val base = name.substring(0, marker)
                val lag = name.substring(marker + 4).toIntOrNull()
                if (base !in LOOKBACK_FEATURES || lag == null || lag !in 0 until LOOKBACK) {
                    return null
                }
                (ring.lagged(lag) ?: current).valueOf(base)
            } else {
                staticValues[name]
            }
            out[index] = candidate?.takeIf { it.isFinite() } ?: 0.0
        }
        return out
    }
}
