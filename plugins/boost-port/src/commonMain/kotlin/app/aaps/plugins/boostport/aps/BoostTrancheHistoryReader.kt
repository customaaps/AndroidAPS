package app.aaps.plugins.boostport.aps

import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.plugins.aps.openAPSBoostV5.TrancheThresholdDeriver
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bounded day-by-day APS reads preserve native-cycle confirm onsets rather than rebucketing. */
internal class BoostTrancheHistoryReader(private val persistence: PersistenceLayer) {
    suspend fun read(now: Long): TrancheThresholdDeriver.Derivation {
        require(now > 0L)
        val start = now - TrancheThresholdDeriver.LOOKBACK_DAYS * 86_400_000L
        val cycles = mutableListOf<Pair<Long, String?>>()
        var from = start
        while (from < now) {
            currentCoroutineContext().ensureActive()
            val end = minOf(from + 86_400_000L, now)
            for (row in persistence.getApsResults(from, end - 1)) {
                val rt = row.rawData() as? RT ?: continue
                if (row.date in from until end && rt.boostV5_active == true && rt.boostV5_state != null)
                    cycles += row.date to rt.boostV5_state
            }
            from = end
        }
        val glucose = persistence.getBgReadingsDataFromTimeToTime(start, now, true)
            .filter { it.isValid && it.timestamp in start..now }.map { it.timestamp to it.value }
        return TrancheThresholdDeriver.derive(TrancheThresholdDeriver.confirmOnsets(cycles), glucose)
    }
}
