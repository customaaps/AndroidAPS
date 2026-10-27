package app.aaps.plugins.aps.openAPSBoost.replay

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.db.PersistenceLayer
import kotlin.math.abs
import kotlin.math.min

/** Source history windows captured at the same instant as the target APS inputs. */
internal data class BoostTargetHistory(
    val recentLowBg60: Double,
    val recentLowBg45: Double,
    val brakingProduct: Double,
    val recentSmbVolume60: Double,
    val minutesSinceLastSmb: Double,
)

/**
 * Reads actual target persistence APIs. No wall clock, cache reset or dose is produced.
 * A failed query propagates: missing delivered-insulin history must not be declared zero
 * by an integration caller. Source's 0/720 query-error fallback is deliberately not adopted.
 */
internal object BoostTargetHistoryReader {
    suspend fun read(persistence: PersistenceLayer, atMs: Long): BoostTargetHistory {
        val bg = persistence.getBgReadingsDataFromTimeToTime(atMs - 60 * 60_000L, atMs, ascending = true)
        val recentLow60 = bg.minOfOrNull { it.value } ?: 999.0
        val recentLow45 = bg.filter { it.timestamp >= atMs - 45 * 60_000L }.minOfOrNull { it.value } ?: 999.0
        val braking = bg.windowed(3).mapNotNull { w ->
            val delta1 = w[1].value - w[0].value
            val delta2 = w[2].value - w[1].value
            val improvement = delta2 - delta1
            if (delta2 < 0 && improvement > 0) abs(delta2) * improvement else null
        }.maxOrNull() ?: 0.0
        val smbs = persistence.getBolusesFromTimeToTime(atMs - 12 * 60 * 60_000L, atMs, ascending = false)
            .filter { it.type == BS.Type.SMB && it.isValid }
        // Target BS.amount is already canonical IU; do not convert it from pump units again.
        val volume60 = smbs.filter { it.timestamp >= atMs - 60 * 60_000L }.sumOf { it.amount }
        val sinceLast = smbs.firstOrNull()?.let { min(720.0, (atMs - it.timestamp) / 60_000.0) } ?: 720.0
        return BoostTargetHistory(recentLow60, recentLow45, braking, volume60, sinceLast)
    }
}
