package app.aaps.plugins.boostport.aps

import app.aaps.core.data.model.HR
import app.aaps.core.interfaces.db.PersistenceLayer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.CancellationException

/** Source 18h re-sweep catches delayed overnight batches; live/minute history always wins. */
internal class BoostHealthConnectIngest(private val persistence: PersistenceLayer, private val reader: BoostHeartRateReader) {
    private val mutex = Mutex()
    private val ingested = mutableSetOf<Long>()
    private var lastAttempt: Long? = null
    suspend fun syncIfDue(nowMs: Long, pollMinutes: Int): Int {
        require(nowMs > 0 && pollMinutes >= 1)
        if (!mutex.tryLock()) return 0
        try {
            if (lastAttempt?.let { nowMs >= it && nowMs - it < pollMinutes * 60_000L } == true) return 0
            lastAttempt = nowMs
            val since = nowMs - 18 * 3_600_000L
            val covered = persistence.getHeartRatesFromTimeToTime(since, nowMs).mapTo(mutableSetOf()) { it.timestamp / 60_000L }
            val result = reader.read(nowMs, covered)
            if (result !is BoostHeartRateReader.Result.Ready) return 0
            val seen = mutableSetOf<Long>()
            val candidates = result.samples
                .filter { it.timestampMs in since..nowMs && it.bpm.isFinite() && it.bpm > 0 && it.device.isNotBlank() }
                .filter { it.timestampMs !in ingested && it.timestampMs / 60_000L !in covered && seen.add(it.timestampMs) }
            if (candidates.isEmpty()) { ingested.removeAll { it < since }; return 0 }
            persistence.insertOrUpdateHeartRates(candidates.map { HR(duration = 60_000L,
                timestamp = it.timestampMs, beatsPerMinute = it.bpm, device = it.device) })
            // Unlike source's pre-insert dedupe, a failed transaction remains retryable.
            ingested.addAll(candidates.map { it.timestampMs })
            ingested.removeAll { it < since }
            return candidates.size
        } catch (cancelled: CancellationException) { throw cancelled }
        finally { mutex.unlock() }
    }
}
