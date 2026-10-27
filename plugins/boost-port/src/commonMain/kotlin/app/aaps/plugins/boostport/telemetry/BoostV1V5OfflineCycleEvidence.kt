package app.aaps.plugins.boostport.telemetry

import app.aaps.plugins.aps.openAPSBoost.BoostV1OfflineCycleMath

/**
 * Caller-fed, diagnostic-only combination of V1 scalar math and V5 shadow
 * evidence. No pump calls, active APS plugin, injected services or dosing.
 * Never represents a dose comparison or safety approval.
 */
internal object BoostV1V5OfflineCycleEvidence {
    enum class Finding { EMPTY, NON_MONOTONIC, INVALID_V1_INPUTS, MISSING_V5_EVIDENCE, READY }
    data class Frame(
        val atMs: Long,
        val v1: BoostV1OfflineCycleMath.Input,
        val v5Score: Double?,
        val v5StateLabel: String?
    )
    data class Entry(
        val index: Int,
        val v1: BoostV1OfflineCycleMath.Report,
        val v5Score: Double,
        val v5StateLabel: String
    )
    data class Report(val finding: Finding, val entries: List<Entry> = emptyList(), val firstInvalidIndex: Int? = null)

    fun inspect(frames: List<Frame>): Report {
        if (frames.isEmpty()) return Report(Finding.EMPTY)
        val entries = mutableListOf<Entry>()
        for ((index, frame) in frames.withIndex()) {
            if (frame.atMs < 0 || (index > 0 && frame.atMs <= frames[index - 1].atMs))
                return Report(Finding.NON_MONOTONIC, firstInvalidIndex = index)
            val v1 = BoostV1OfflineCycleMath.inspect(frame.v1)
            if (v1.finding != BoostV1OfflineCycleMath.Finding.READY)
                return Report(Finding.INVALID_V1_INPUTS, firstInvalidIndex = index)
            val score = frame.v5Score
            val label = frame.v5StateLabel
            if (score == null || !score.isFinite() || label.isNullOrBlank())
                return Report(Finding.MISSING_V5_EVIDENCE, firstInvalidIndex = index)
            entries.add(Entry(index, v1, score, label))
        }
        return Report(Finding.READY, entries)
    }
}
