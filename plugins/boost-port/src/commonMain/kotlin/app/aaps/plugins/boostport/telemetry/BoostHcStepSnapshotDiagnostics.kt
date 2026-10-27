package app.aaps.plugins.boostport.telemetry

/**
 * Pure read-only reconstruction of HealthConnectStepsIngest's 28-day
 * single-source/per-source local-day snapshot, without any Android I/O.
 *
 * Caller supplies an already completed, paginated StepsRecord snapshot.
 * An incomplete response is rejected, never translated into a zero-step day.
 * The original code attributes each interval to its *start* local day rather
 * than splitting intervals at midnight; we retain that representation and
 * report cross-midnight intervals as a quality warning.
 *
 * No Health Connect permissions, reads, NSClient, Preferences, storage,
 * profile/ISF adjustment, APS algorithm or pump interface.
 */
internal object BoostHcStepSnapshotDiagnostics {
    const val WINDOW_DAYS = 28L
    const val POLL_INTERVAL_MS = 60L * 60_000L
    private const val DAY_MS = 86_400_000L
    private const val MAX_RECORDS = 50_000
    private const val MAX_SOURCES = 64

    data class Record(
        val source: String,
        val startMs: Long,
        val endMs: Long,
        val steps: Long,
        val phoneDevice: Boolean = false,
    )

    data class Snapshot(
        val todayLocalDayIndex: Long,
        val perSourceDay: Map<String, Map<Long, Long>>,
        val phoneDeviceDay: Map<Long, Long>,
        val chosenSource: String?,
        val todayStepsBySource: Map<String, Long>,
        val crossesLocalMidnight: Int,
        val duplicateRecordsSkipped: Int,
    )

    private fun localDay(ms: Long, offsetMs: Long): Long? {
        if (ms < 0L ||
            offsetMs !in (-18L * 3_600_000L)..(18L * 3_600_000L) ||
            (offsetMs > 0L && ms > Long.MAX_VALUE - offsetMs)
        ) return null
        val local = ms + offsetMs
        val quotient = local / DAY_MS
        return if (local < 0L && local % DAY_MS != 0L) quotient - 1L else quotient
    }

    private fun plusBounded(previous: Long, next: Long): Long =
        if (next > Long.MAX_VALUE - previous) Long.MAX_VALUE else previous + next

    fun build(
        records: List<Record>,
        allPagesComplete: Boolean,
        nowMs: Long,
        localOffsetMs: Long,
    ): Snapshot? {
        if (!allPagesComplete || nowMs <= 0L ||
            records.size > MAX_RECORDS
        ) return null
        val today = localDay(nowMs, localOffsetMs) ?: return null
        val sinceMs = (nowMs - WINDOW_DAYS * DAY_MS).coerceAtLeast(0L)
        val perSource = linkedMapOf<String, MutableMap<Long, Long>>()
        val phone = linkedMapOf<Long, Long>()
        val seen = mutableSetOf<Record>()
        var duplicates = 0
        var crossing = 0

        for (record in records) {
            if (record.source.isBlank() || record.source.length > 200 ||
                record.steps < 0L || record.startMs < sinceMs ||
                record.endMs > nowMs || record.startMs >= record.endMs
            ) return null
            if (!seen.add(record)) {
                duplicates++
                continue
            }
            val startDay = localDay(record.startMs, localOffsetMs) ?: return null
            val endDay = localDay(record.endMs - 1L, localOffsetMs) ?: return null
            if (startDay != endDay) crossing++
            if (record.source !in perSource && perSource.size >= MAX_SOURCES)
                return null
            val byDay = perSource.getOrPut(record.source) { linkedMapOf() }
            byDay[startDay] = plusBounded(byDay[startDay] ?: 0L, record.steps)
            if (record.phoneDevice) {
                phone[startDay] = plusBounded(phone[startDay] ?: 0L, record.steps)
            }
        }

        val immutable = perSource.mapValues { (_, v) -> v.toMap() }
        val chosen = HealthConnectStepCoverage.resolve(immutable).chosenSource
        return Snapshot(
            todayLocalDayIndex = today,
            perSourceDay = immutable,
            phoneDeviceDay = phone.toMap(),
            chosenSource = chosen,
            todayStepsBySource = immutable.mapValues { (_, days) ->
                days[today] ?: 0L
            },
            crossesLocalMidnight = crossing,
            duplicateRecordsSkipped = duplicates,
        )
    }
}
