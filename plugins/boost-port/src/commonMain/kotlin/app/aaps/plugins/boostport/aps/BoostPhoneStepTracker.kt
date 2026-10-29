package app.aaps.plugins.boostport.aps

import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.plugins.boostport.telemetry.StepBucketWindowDiagnostics
import app.aaps.plugins.boostport.telemetry.PhoneStepDayLedgerDiagnostics

/** Captured-clock StepService adapter: tested source bucket/day arithmetic, synchronized across sensor and APS threads. */
internal class BoostPhoneStepTracker {
    private val lock = AapsLock()
    private var buckets = StepBucketWindowDiagnostics.State()
    private var day = PhoneStepDayLedgerDiagnostics.State()
    private var firstObservation: Long? = null
    fun accept(raw: Long, atMs: Long) = lock.withLock {
        StepBucketWindowDiagnostics.accept(buckets, raw, atMs)?.let {
            buckets = it
            if (firstObservation == null) firstObservation = atMs
        }
    }
    fun seedToday(localDay: Long, steps: Long) = lock.withLock {
        PhoneStepDayLedgerDiagnostics.observeRaw(day, localDay, buckets.previousRawCount)?.let { day = it.state }
        PhoneStepDayLedgerDiagnostics.seedHealthConnect(day, localDay, steps, buckets.previousRawCount)?.let { day = it.state }
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
    /** Completed windows only; the pre-registration part of the first bucket is not a measured zero. */
    fun completedRows(nowMs: Long, offsetMinutes: Int): List<SC> = lock.withLock {
        val first = firstObservation ?: return@withLock emptyList()
        val end = nowMs / 300_000L * 300_000L
        if (first >= end || buckets.lastEventMs?.let { it > nowMs } == true) return@withLock emptyList()
        val windows = listOf(5, 10, 15, 30, 60).map {
            StepBucketWindowDiagnostics.completedWindow(buckets, nowMs, it)
                ?: return@withLock emptyList()
        }
        if (windows.any { !it.feedKnown }) return@withLock emptyList()
        listOf(SC(duration = 300_000L, timestamp = end, steps5min = windows[0].steps ?: 0,
            steps10min = windows[1].steps ?: 0, steps15min = windows[2].steps ?: 0,
            steps30min = windows[3].steps ?: 0, steps60min = windows[4].steps ?: 0,
            steps180min = 0, device = BoostStepCountPublisher.DEVICE, utcOffset = offsetMinutes * 60_000L))
    }
}

