package app.aaps.plugins.boostport.telemetry

/**
 * Pure, offline extraction of HealthConnectHrIngest's lookback backfill
 * selection with the minute-level live-feed precedence (2026-06-24 fix).
 *
 * This is a preview of candidates, NOT an ingestion worker. In particular
 * selected timestamps are not marked as persisted: a future Android adapter
 * would have to acknowledge completed writes separately. No Health Connect
 * reads, AAPS database writes, Preferences, APS cycle or pump interaction.
 */
internal object BoostHcHrBackfillDiagnostics {
    const val DEFAULT_LOOKBACK_MS = BoostHcHrPollDiagnostics.LOOKBACK_MS
    private const val MINUTE_MS = 60_000L
    private const val MAX_SAMPLES = 50_000
    private const val MAX_EXISTING = 100_000
    private const val MAX_LOOKBACK_MS = 48L * 60L * MINUTE_MS

    data class Sample(
        val timestampMs: Long,
        val bpm: Double,
        val device: String,
    )

    data class Preview(
        val candidates: List<Sample>,
        val candidateTimestampsMs: Set<Long>,
        val existingLiveMinutesSkipped: Int,
        val alreadyIngestedTimestampsSkipped: Int,
        val sameMinuteDuplicatesSkipped: Int,
        val invalidSamplesSkipped: Int,
        val outsideWindowSkipped: Int,
    )

    /**
     * Assemble offline candidates for an 18h trailing re-sweep. Previous
     * sample timestamps represent caller-confirmed ingestion only. Existing
     * live-feed minutes win over Health Connect records, as in source.
     *
     * Null means the input snapshot exceeds bounded diagnostic budgets or
     * contains unusable clock/permission-context data. Rejected individual
     * HC samples are counted but do not poison otherwise valid records.
     */
    fun preview(
        records: List<Sample>,
        existingLiveHrMinutes: Set<Long>,
        previouslyIngestedSampleMs: Set<Long>,
        nowMs: Long,
        lookbackMs: Long = DEFAULT_LOOKBACK_MS,
    ): Preview? {
        if (nowMs <= 0L || lookbackMs !in 1L..MAX_LOOKBACK_MS ||
            records.size > MAX_SAMPLES ||
            existingLiveHrMinutes.size > MAX_EXISTING ||
            previouslyIngestedSampleMs.size > MAX_EXISTING ||
            existingLiveHrMinutes.any { it < 0L || it > nowMs / MINUTE_MS } ||
            previouslyIngestedSampleMs.any { it < 0L || it > nowMs }
        ) return null

        val since = (nowMs - lookbackMs).coerceAtLeast(0L)
        val ingested = previouslyIngestedSampleMs.asSequence()
            .filter { it >= since }
            .toMutableSet()
        val alreadyCovered = existingLiveHrMinutes.toMutableSet()
        val pickedMinute = mutableSetOf<Long>()
        val selected = mutableListOf<Sample>()
        var live = 0
        var known = 0
        var duplicate = 0
        var invalid = 0
        var outside = 0

        for (sample in records.sortedWith(
            compareBy<Sample> { it.timestampMs }.thenBy { it.device }
        )) {
            if (sample.timestampMs < since || sample.timestampMs > nowMs) {
                outside++
                continue
            }
            if (!sample.bpm.isFinite() || sample.bpm <= 0.0 ||
                sample.device.isBlank() || sample.device.length > 200
            ) {
                invalid++
                continue
            }
            if (sample.timestampMs in ingested) {
                known++
                continue
            }
            val minute = sample.timestampMs / MINUTE_MS
            if (minute in alreadyCovered) {
                live++
                continue
            }
            if (!pickedMinute.add(minute)) {
                duplicate++
                continue
            }
            selected += sample
            // Only local in-preview duplicate suppression. Not persisted.
            ingested += sample.timestampMs
        }

        return Preview(
            candidates = selected.toList(),
            candidateTimestampsMs = selected.mapTo(mutableSetOf()) { it.timestampMs },
            existingLiveMinutesSkipped = live,
            alreadyIngestedTimestampsSkipped = known,
            sameMinuteDuplicatesSkipped = duplicate,
            invalidSamplesSkipped = invalid,
            outsideWindowSkipped = outside,
        )
    }
}
