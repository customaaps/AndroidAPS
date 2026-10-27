package app.aaps.plugins.boostport.telemetry

/**
 * Batch-only offline restore inspection. Source maps are supplied by the caller.
 * Never reads/writes Preferences, resets a real plugin, or accesses APS/pump APIs.
 */
internal object BoostV5OfflineRestoreBatch {
    data class Frame(val timeMs: Long, val persisted: Map<String, String>?)
    data class Entry(
        val index: Int,
        val timeMs: Long,
        val finding: BoostV5OfflineRestorePreflight.Finding,
        val futureFields: Set<String>,
    )
    data class Summary(
        val entries: List<Entry>,
        val ready: Int,
        val absent: Int,
        val rejected: Int,
        val chronological: Boolean,
    )

    fun inspect(frames: List<Frame>): Summary {
        val entries = frames.mapIndexed { index, frame ->
            val report = BoostV5OfflineRestorePreflight.inspect(frame.persisted, frame.timeMs)
            Entry(index, frame.timeMs, report.finding, report.futureFields)
        }
        var chronological = true
        for (i in 1 until frames.size) {
            if (frames[i].timeMs <= frames[i - 1].timeMs) chronological = false
        }
        val ready = entries.count { it.finding == BoostV5OfflineRestorePreflight.Finding.READY }
        val absent = entries.count { it.finding == BoostV5OfflineRestorePreflight.Finding.ABSENT }
        return Summary(entries, ready, absent, entries.size - ready - absent, chronological)
    }
}
