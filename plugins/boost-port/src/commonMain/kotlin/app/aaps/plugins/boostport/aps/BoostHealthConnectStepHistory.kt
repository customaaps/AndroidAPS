package app.aaps.plugins.boostport.aps

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.plugins.boostport.telemetry.BoostHcStepSnapshotDiagnostics
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

data class BoostStepHistorySnapshot(val capturedAtMs: Long, val offsetMinutes: Int,
    val todayIndex: Long, val perSourceDay: Map<String, Map<Long, Long>>,
    val phoneDeviceDay: Map<Long, Long>, val chosenSource: String?)

interface BoostStepHistoryReader {
    data class Sample(val source: String, val startMs: Long, val endMs: Long, val steps: Long,
        val phoneDevice: Boolean = false)
    sealed interface Result {
        data class Ready(val samples: List<Sample>) : Result
        data class Unavailable(val reason: String) : Result
    }
    suspend fun read(nowMs: Long): Result
}

/** Hourly full-window reads, owned/cancelled by one activity lease. No partial-page publication. */
internal class BoostHealthConnectStepHistory(private val reader: BoostStepHistoryReader) {
    private val polling = Mutex()
    private val lock = AapsLock()
    private var lastAttempt: Long? = null
    private var value: BoostStepHistorySnapshot? = null
    private var generation = 0L

    fun clear() = lock.withLock { value = null; lastAttempt = null; generation++ }
    fun snapshot(nowMs: Long, offsetMinutes: Int): BoostStepHistorySnapshot? = lock.withLock {
        value?.takeIf { nowMs >= it.capturedAtMs && nowMs - it.capturedAtMs <= 2 * 3_600_000L &&
            it.offsetMinutes == offsetMinutes }
    }
    suspend fun syncIfDue(nowMs: Long, offsetMinutes: Int) {
        require(nowMs > 0 && offsetMinutes in -1080..1080)
        if (!polling.tryLock()) return
        try {
            val revision = lock.withLock {
                if (lastAttempt?.let { nowMs >= it && nowMs - it < 3_600_000L } == true) null
                else { lastAttempt = nowMs; generation }
            } ?: return
            val result = reader.read(nowMs)
            currentCoroutineContext().ensureActive()
            val built = (result as? BoostStepHistoryReader.Result.Ready)?.let { ready ->
                BoostHcStepSnapshotDiagnostics.build(ready.samples.map {
                    BoostHcStepSnapshotDiagnostics.Record(it.source, it.startMs, it.endMs, it.steps, it.phoneDevice)
                }, true, nowMs, offsetMinutes * 60_000L)
            }
            val next = built?.let { BoostStepHistorySnapshot(nowMs, offsetMinutes, it.todayLocalDayIndex,
                it.perSourceDay, it.phoneDeviceDay, it.chosenSource) }
            lock.withLock { if (generation == revision) value = next }
        } finally { polling.unlock() }
    }
}
