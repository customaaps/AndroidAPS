package app.aaps.plugins.aps.openAPSBoost

/**
 * Read-only replay of archived V1/V6 setting transition rules.
 * No preference writes, APS wiring, ISF changes, or pump interaction.
 */
internal object BoostTddTransitionOfflineReplay {
    data class Frame(
        val atMs: Long,
        val useTdd: Boolean,
        val engineMode: String,
        val adjustSens: Boolean,
        val velocityPct: Double
    )
    enum class Finding { EMPTY, VALID, INVALID_TIME, INVALID_VALUE, INVALID_SEQUENCE }
    data class Transition(
        val index: Int,
        val atMs: Long,
        val result: BoostTddSettingTransition
    )
    data class Report(
        val finding: Finding,
        val transitions: List<Transition> = emptyList(),
        val firstInvalidIndex: Int? = null
    )

    fun inspect(frames: List<Frame>): Report {
        if (frames.isEmpty()) return Report(Finding.EMPTY)
        val transitions = mutableListOf<Transition>()
        var previous: Frame? = null
        for ((index, frame) in frames.withIndex()) {
            if (frame.atMs < 0L) return Report(Finding.INVALID_TIME, firstInvalidIndex = index)
            if (!frame.velocityPct.isFinite() || frame.velocityPct < 0.0)
                return Report(Finding.INVALID_VALUE, firstInvalidIndex = index)
            val prior = previous
            if (prior != null && frame.atMs <= prior.atMs)
                return Report(Finding.INVALID_SEQUENCE, firstInvalidIndex = index)
            val result = boostTddSettingsTransition(
                useTdd = frame.useTdd,
                adjustSens = frame.adjustSens,
                velocityPct = frame.velocityPct,
                switchedToV6 = boostSwitchedToV6(prior?.engineMode ?: "", frame.engineMode == "v6"),
                tddJustEnabled = prior != null && !prior.useTdd && frame.useTdd
            )
            transitions.add(Transition(index, frame.atMs, result))
            previous = frame
        }
        return Report(Finding.VALID, transitions)
    }
}
