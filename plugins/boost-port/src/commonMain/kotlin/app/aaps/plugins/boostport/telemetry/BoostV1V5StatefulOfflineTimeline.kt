package app.aaps.plugins.boostport.telemetry

import app.aaps.plugins.aps.openAPSBoost.BoostV1OfflineCycleMath
import app.aaps.plugins.aps.openAPSBoostV5.V5Inputs
import app.aaps.plugins.aps.openAPSBoostV5.V5PersistedState

/**
 * Runs V1 read-only scalar diagnostics alongside the real pure V5 stateful
 * shadow replay. Every frame uses the same clock. Nothing is registered with
 * AAPS; no doses, decisions or pump commands are exposed.
 */
internal object BoostV1V5StatefulOfflineTimeline {
    private const val MAX_FRAMES = 1000
    enum class Finding { EMPTY, TOO_MANY_FRAMES, INVALID_CLOCK, INVALID_V1, INVALID_V5, READY }
    data class Frame(
        val atMs: Long,
        val v1: BoostV1OfflineCycleMath.Input,
        val v5: V5Inputs
    )
    data class Entry(
        val atMs: Long,
        val v1: BoostV1OfflineCycleMath.Report,
        val v5Score: Double,
        val v5MealState: String
    )
    data class Report(
        val finding: Finding,
        val entries: List<Entry> = emptyList(),
        val firstInvalidIndex: Int? = null,
        val canDeliverInsulin: Boolean = false
    )

    fun inspect(
        frames: List<Frame>,
        initialV5State: V5PersistedState = V5PersistedState()
    ): Report {
        if (frames.isEmpty()) return Report(Finding.EMPTY)
        if (frames.size > MAX_FRAMES) return Report(Finding.TOO_MANY_FRAMES)
        // Validate every V1 snapshot before any V5 invocation.
        val v1 = ArrayList<BoostV1OfflineCycleMath.Report>(frames.size)
        for ((index, frame) in frames.withIndex()) {
            if (frame.atMs <= 0L || frame.v5.nowMs != frame.atMs ||
                (index > 0 && frame.atMs <= frames[index - 1].atMs))
                return Report(Finding.INVALID_CLOCK, firstInvalidIndex = index)
            val diagnostic = BoostV1OfflineCycleMath.inspect(frame.v1)
            if (diagnostic.finding != BoostV1OfflineCycleMath.Finding.READY)
                return Report(Finding.INVALID_V1, firstInvalidIndex = index)
            v1.add(diagnostic)
        }
        // V5 state is advanced only in the separate non-dosing shadow runner.
        val replay = BoostV5OfflineStatefulReplay.inspect(frames.map { it.v5 }, initialV5State)
        if (replay.finding != BoostV5OfflineStatefulReplay.Finding.READY ||
            replay.observations.size != frames.size)
            return Report(Finding.INVALID_V5, firstInvalidIndex = replay.firstInvalidIndex)
        val entries = frames.indices.map { index ->
            val observation = replay.observations[index]
            Entry(frames[index].atMs, v1[index], observation.score, observation.mealState)
        }
        return Report(Finding.READY, entries)
    }
}
