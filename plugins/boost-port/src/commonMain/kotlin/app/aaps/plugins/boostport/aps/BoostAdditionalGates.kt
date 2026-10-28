package app.aaps.plugins.boostport.aps

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.plugins.aps.openAPSBoost.BoostV1ReplayEngine
import app.aaps.plugins.aps.openAPSBoostV5.composedFloorAllowedByTbr
import app.aaps.plugins.aps.openAPSBoostV5.aggressiveEarlyConfirmAllowed
import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid

/** Source's hourly, trailing-14-day gate shared by the two floors and aggressive early confirmation. */
internal class BoostFloorHistoryGate(private val persistence: PersistenceLayer) {
    private var below63: Double? = null
    private var below70: Double? = null
    private var computedAt = 0L
    data class Decision(val floorsAllowed: Boolean, val aggressiveEarlyConfirmAllowed: Boolean)

    suspend fun forCycle(atMs: Long, floorsRequested: Boolean, aggressiveRequested: Boolean): Decision {
        if (!floorsRequested && !aggressiveRequested) return Decision(false, false)
        val permitted = allowed(atMs) // one shared read even while history is insufficient
        return Decision(floorsRequested && permitted,
            aggressiveEarlyConfirmAllowed(aggressiveRequested, below63, below70))
    }

    suspend fun allowed(atMs: Long): Boolean {
        require(atMs > 0L)
        if (below63 == null || atMs < computedAt || atMs - computedAt >= 3_600_000L) {
            val since = atMs - 14 * 86_400_000L
            val rows = try {
                persistence.getBgReadingsDataFromTimeToTime(since, atMs, true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                below63 = null; below70 = null; computedAt = atMs
                return false
            }
            if (rows.size >= 1000 && rows.all { it.isValid && it.value.isFinite() && it.value >= 1.0 && it.timestamp in since..atMs }) {
                below63 = 100.0 * rows.count { it.value >= 1.0 && it.value < 63.0 } / rows.size
                below70 = 100.0 * rows.count { it.value >= 1.0 && it.value < 70.0 } / rows.size
            } else { below63 = null; below70 = null }
            computedAt = atMs
        }
        return composedFloorAllowedByTbr(below63, below70)
    }
}

/** Source install-seeded local-day crossover; never invent a new seed on restart. */
internal object BoostTightRampPolicy {
    fun cap(store: KeyValueStore, enabled: Boolean, localDay: Long,
        newSeed: () -> String = { Uuid.random().toString() }): Double? {
        if (!enabled) return null
        val key = BoostApsSettingKeys.ApsBoostTrialSeed.key
        var seed = store.getString(key, "")
        if (seed.isEmpty()) {
            seed = newSeed()
            require(seed.isNotEmpty())
            store.putString(key, seed)
        }
        return if (BoostV1ReplayEngine.tightRampArm(seed, localDay)) BoostV1ReplayEngine.TIGHT_RAMP_CAP else null
    }
}

