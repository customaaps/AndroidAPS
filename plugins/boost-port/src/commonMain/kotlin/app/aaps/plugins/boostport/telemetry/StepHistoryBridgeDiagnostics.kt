package app.aaps.plugins.boostport.telemetry

/**
 * Pure KMP port of the PHONE-ANCHORED multi-source step-history bridge in
 * legacy DailyStepHistoryTracker (2026-06-29 and 2026-07-03 fixes).
 *
 * A continuous phone history calibrates each distinct wearable era even
 * when two different watches share NO overlap. Without >=3 phone↔wear
 * overlapping nonzero days the raw count remains visible and flagged
 * uncalibrated; in all cases the highest available completed-day count
 * wins, to prevent a false inactivity artifact from a partial phone sync.
 *
 * No sensors, JSON persistence, Health Connect reader, AAPS algorithm,
 * ISF/profile modification, insulin dosing or pump interfaces.
 */
internal object StepHistoryBridgeDiagnostics {
    const val MIN_OVERLAP_DAYS = 3
    private const val MAX_SOURCES = 32

    data class MultiHistory(
        val histories: Map<String, DailyStepActivityDiagnostics.History> = emptyMap(),
    )

    data class Bridge(
        val history: DailyStepActivityDiagnostics.History,
        val calibrated: Boolean,
        val donorsUsed: List<String>,
        val heldNote: String?,
    )

    private data class Candidate(
        val source: String,
        val steps: Int,
        val calibrated: Boolean,
    )

    private fun tier(source: String): Int = when {
        source == "wear" -> 0
        source == "garmin" -> 1
        source.startsWith("hc:") -> 2
        source == "phone" -> 3
        else -> 4
    }

    private fun valid(multi: MultiHistory): Boolean =
        multi.histories.size <= MAX_SOURCES &&
            multi.histories.all { (source, history) ->
                IntradayStepBankDiagnostics.canonical(source) == source &&
                    history.days.size <= 500 &&
                    history.days.all { (day, total) ->
                        day >= 0L && day == total.dayIndex &&
                            total.steps >= 0 && total.source == source
                    }
            }

    /** Merge completed days for one canonical source, holding the higher total. */
    fun mergeSource(
        previous: MultiHistory,
        rawSource: String,
        totals: List<DailyStepActivityDiagnostics.DailyTotal>,
        todayIndex: Long,
    ): MultiHistory? {
        if (!valid(previous)) return null
        val source = IntradayStepBankDiagnostics.canonical(rawSource) ?: return null
        val normalized = totals.map { it.copy(source = source) }
        val merged = DailyStepActivityDiagnostics.merge(
            previous.histories[source] ?: DailyStepActivityDiagnostics.History(),
            normalized,
            todayIndex,
        ) ?: return null
        val next = previous.histories.toMutableMap()
        if (merged.days.isEmpty()) next.remove(source) else next[source] = merged
        if (next.size > MAX_SOURCES) return null
        return MultiHistory(next.toMap())
    }

    /** Median of positive overlap-day active:donor ratios, never single-day calibration. */
    fun calibration(
        active: DailyStepActivityDiagnostics.History,
        donor: DailyStepActivityDiagnostics.History,
    ): Double? {
        val ratios = active.days.mapNotNull { (day, a) ->
            val b = donor.days[day] ?: return@mapNotNull null
            if (a.steps <= 0 || b.steps <= 0) null
            else a.steps.toDouble() / b.steps.toDouble()
        }.filter { it.isFinite() && it > 0.0 }.sorted()
        return if (ratios.size < MIN_OVERLAP_DAYS) null else ratios[ratios.size / 2]
    }

    /** Comparable local completed-day window in phone-equivalent units. */
    fun phoneAnchored(multi: MultiHistory, todayIndex: Long): Bridge? {
        if (!valid(multi) || todayIndex < 0L) return null
        val phone = multi.histories["phone"] ?: DailyStepActivityDiagnostics.History()
        val wearableSources = multi.histories.keys
            .filter { it != "phone" }
            .sortedWith(compareBy<String> { tier(it) }.thenBy { it })
        val calibrationBySource = wearableSources.associateWith { name ->
            calibration(phone, multi.histories.getValue(name))
        }
        val results = linkedMapOf<Long, DailyStepActivityDiagnostics.DailyTotal>()
        val donors = linkedSetOf<String>()
        var uncalibrated = false
        var heldNote: String? = null
        val start = (todayIndex - DailyStepActivityDiagnostics.WINDOW_DAYS).coerceAtLeast(0L)

        for (day in start until todayIndex) {
            val candidates = mutableListOf<Candidate>()
            phone.days[day]?.let { candidates += Candidate("phone", it.steps, true) }
            for (source in wearableSources) {
                val raw = multi.histories.getValue(source).days[day] ?: continue
                val factor = calibrationBySource[source]
                val value = if (factor != null) {
                    val scaled = raw.steps.toDouble() * factor
                    if (!scaled.isFinite() || scaled > Int.MAX_VALUE) return null
                    scaled.toInt()
                } else raw.steps
                candidates += Candidate(source, value, factor != null)
            }
            val winner = candidates.maxWithOrNull(
                compareBy<Candidate> { it.steps }.thenBy { -tier(it.source) }
            ) ?: continue
            results[day] = DailyStepActivityDiagnostics.DailyTotal(
                day, winner.steps, winner.source,
            )
            if (winner.source != "phone") {
                donors += winner.source
                if (!winner.calibrated) uncalibrated = true
            }
            if (day == todayIndex - 1L) {
                val runnerUp = candidates
                    .filter { it.source != winner.source }
                    .maxByOrNull { it.steps }
                if (runnerUp != null && winner.steps > runnerUp.steps) {
                    heldNote = "held ${winner.source} ${winner.steps} over " +
                        "${runnerUp.source} ${runnerUp.steps}"
                }
            }
        }
        return Bridge(DailyStepActivityDiagnostics.History(results.toMap()),
            calibrated = !uncalibrated, donorsUsed = donors.toList(), heldNote = heldNote)
    }

    /**
     * Today's counts compared with yesterday's *phone-anchored* baseline.
     * Falls back to the unchanged source-native count when calibration
     * has insufficient overlapping days; never applies an ISF factor.
     */
    fun toPhoneUnits(
        steps: Int,
        rawSource: String?,
        multi: MultiHistory,
    ): Int? {
        if (steps < 0 || !valid(multi)) return null
        if (rawSource == null) return steps
        val src = IntradayStepBankDiagnostics.canonical(rawSource) ?: return null
        if (src == "phone") return steps
        val phone = multi.histories["phone"] ?: return steps
        val donor = multi.histories[src] ?: return steps
        val ratio = calibration(phone, donor) ?: return steps
        val scaled = steps.toDouble() * ratio
        return if (!scaled.isFinite() || scaled > Int.MAX_VALUE) null else scaled.toInt()
    }
}
