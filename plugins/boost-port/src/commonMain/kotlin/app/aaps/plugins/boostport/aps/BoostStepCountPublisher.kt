package app.aaps.plugins.boostport.aps

import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.db.PersistenceLayer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One persisted five-minute series shared by the graph and the existing APS SC reader.
 * Phone/HC can describe the same steps: take per-window maxima, never add the feeds.
 * No sample is emitted for a source which has not actually reported.
 */
internal class BoostStepCountPublisher(private val persistence: PersistenceLayer) {
    private val mutex = Mutex()

    suspend fun publish(samples: List<SC>, nowMs: Long): Int = mutex.withLock {
        val valid = samples.filter { it.isValid && it.timestamp > 0 && it.timestamp <= nowMs &&
            it.duration == BUCKET_MS && listOf(it.steps5min, it.steps10min, it.steps15min,
                it.steps30min, it.steps60min, it.steps180min).all { count -> count >= 0 } }
        if (valid.isEmpty()) return@withLock 0
        val existing = persistence.getStepsCountFromTimeToTime(valid.minOf { it.timestamp }, nowMs)
            .filter { it.device == DEVICE && it.isValid }.associateBy { it.timestamp }
        val writes = valid.groupBy { it.timestamp }.mapNotNull { (time, rows) ->
            val old = existing[time]
            val combined = rows + listOfNotNull(old)
            val next = (old ?: rows.first()).copy(device = DEVICE, utcOffset = rows.first().utcOffset,
                steps5min = combined.maxOf { it.steps5min }, steps10min = combined.maxOf { it.steps10min },
                steps15min = combined.maxOf { it.steps15min }, steps30min = combined.maxOf { it.steps30min },
                steps60min = combined.maxOf { it.steps60min }, steps180min = combined.maxOf { it.steps180min })
            next.takeUnless { old != null && old.contentEqualsTo(it) }
        }
        currentCoroutineContext().ensureActive()
        if (writes.isNotEmpty()) persistence.insertOrUpdateStepsCounts(writes)
        writes.size
    }

    companion object {
        const val BUCKET_MS = 300_000L
        const val DEVICE = "Boost:steps"

        /** SDK aggregates have already applied Health Connect's source-priority deduplication.
         * Sparse buckets remain absent: no zero row is invented for a missing interval.
         */
        fun healthConnectRows(buckets: Map<Long, Long>, nowMs: Long, offsetMs: Long): List<SC> {
            val valid = buckets.filter { (end, count) ->
                end > 0 && end <= nowMs && end % BUCKET_MS == 0L && count in 0L..1500L
            }
            return valid.keys.sorted().map { end ->
                fun count(minutes: Int): Int = valid.filterKeys { it > end - minutes * 60_000L && it <= end }
                    .values.sum().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                SC(duration = BUCKET_MS, timestamp = end, steps5min = count(5), steps10min = count(10),
                    steps15min = count(15), steps30min = count(30), steps60min = count(60),
                    steps180min = count(180), device = DEVICE, utcOffset = offsetMs)
            }
        }
    }
}
