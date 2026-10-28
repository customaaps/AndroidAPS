package app.aaps.plugins.boostport.aps

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.plugins.boostport.telemetry.StepBucketWindowDiagnostics
import app.aaps.plugins.boostport.telemetry.PhoneStepDayLedgerDiagnostics

/** Captured-clock StepService adapter: tested source bucket/day arithmetic, synchronized across sensor and APS threads. */
internal class BoostPhoneStepTracker {
    private val lock = AapsLock()
    private var buckets = StepBucketWindowDiagnostics.State()
    private var day = PhoneStepDayLedgerDiagnostics.State()
    fun accept(raw: Long, atMs: Long) = lock.withLock {
        StepBucketWindowDiagnostics.accept(buckets, raw, atMs)?.let { buckets = it }
    }
    fun snapshot(nowMs: Long, offsetMinutes: Int): BoostApsActivitySource.Snapshot = lock.withLock {
        val localDay = (nowMs + offsetMinutes * 60_000L) / 86_400_000L
        val daily = PhoneStepDayLedgerDiagnostics.observeRaw(day, localDay, buckets.previousRawCount)
            ?: return@withLock BoostApsActivitySource.Snapshot()
        day = daily.state
        val windows = listOf(5, 15, 30, 60).map {
            StepBucketWindowDiagnostics.completedWindow(buckets, nowMs, it)
                ?: return@withLock BoostApsActivitySource.Snapshot()
        }
        BoostApsActivitySource.Snapshot(windows[0].steps ?: 0, windows[1].steps ?: 0,
            windows[2].steps ?: 0, windows[3].steps ?: 0,
            (daily.reportedStepsToday ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            windows.all { it.feedKnown })
    }
}
