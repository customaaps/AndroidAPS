package app.aaps.plugins.boostport.telemetry

/**
 * A bounded, read-only V1 diagnostic timeline. It never calculates a dose,
 * exposes APSResult or accesses AndroidAPS/pump dependencies.
 */
internal object BoostV1UnifiedOfflineTimeline {
    private const val MAX_FRAMES = 1000
    enum class Finding { EMPTY, TOO_MANY, INVALID_CLOCK, INVALID_FRAME, READY }
    data class Frame(val atMs: Long, val input: BoostV1UnifiedOfflineInspection.Input)
    data class Entry(
        val atMs: Long,
        val expectedDelta: Double?,
        val shadowIsf: Double?
    )
    data class Report(
        val finding: Finding,
        val entries: List<Entry> = emptyList(),
        val invalidIndex: Int? = null,
        val canDeliverInsulin: Boolean = false
    )

    fun inspect(frames: List<Frame>): Report {
        if (frames.isEmpty()) return Report(Finding.EMPTY)
        if (frames.size > MAX_FRAMES) return Report(Finding.TOO_MANY)
        val entries = ArrayList<Entry>(frames.size)
        var previous = 0L
        for ((index, frame) in frames.withIndex()) {
            if (frame.atMs <= previous || frame.atMs != frame.input.snapshot.comparisonTimeMs ||
                frame.atMs != frame.input.snapshot.engine.nowMs)
                return Report(Finding.INVALID_CLOCK, invalidIndex = index)
            val result = BoostV1UnifiedOfflineInspection.inspect(frame.input)
            if (result.finding != BoostV1UnifiedOfflineInspection.Finding.READY)
                return Report(Finding.INVALID_FRAME, invalidIndex = index)
            entries.add(Entry(frame.atMs, result.expectedDelta, result.shadowIsf))
            previous = frame.atMs
        }
        return Report(Finding.READY, entries)
    }
}
