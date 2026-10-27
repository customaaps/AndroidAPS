package app.aaps.plugins.aps.openAPSBoostV5

/**
 * Pure, read-only diagnostic for snapshots collected in chronological order.
 * It rejects backwards state clocks and a disappearing session lock while the
 * same recorded commit is still in effect. A real session unlock must be
 * independently justified by the archived meal state machine.
 *
 * This is NOT an APS safety gate, scheduling component, or dosing engine.
 */
internal object V5OfflineReplayContinuity {
    enum class Finding {
        MISSING_OR_INVALID_SNAPSHOT,
        CLOCK_REGRESSION,
        SESSION_LOCK_LOST,
        OFFLINE_CONTINUITY_DESCRIBED
    }

    data class Report(
        val finding: Finding,
        val inspectedFrames: Int,
        val rejectedIndex: Int? = null,
        val liveInvocationAllowed: Boolean = false,
        val insulinDeliveryAllowed: Boolean = false
    )

    fun inspect(orderedSnapshots: List<Map<String, String>?>): Report {
        if (orderedSnapshots.isEmpty()) {
            return Report(Finding.MISSING_OR_INVALID_SNAPSHOT, 0)
        }
        var previous: MealHypothesisState? = null
        for ((index, fields) in orderedSnapshots.withIndex()) {
            val restored = V5OfflineStateHandoff.restore(fields).state
                ?: return Report(Finding.MISSING_OR_INVALID_SNAPSHOT, index, index)
            val meal = restored.mealHypothesis
            val prior = previous
            if (prior != null) {
                if ((prior.lastAgeMs > 0 && meal.lastAgeMs > 0 && meal.lastAgeMs < prior.lastAgeMs) ||
                    (prior.lastCommitMs > 0 && meal.lastCommitMs > 0 && meal.lastCommitMs < prior.lastCommitMs)) {
                    return Report(Finding.CLOCK_REGRESSION, index, index)
                }
                if (prior.committedInSession && !meal.committedInSession &&
                    prior.lastCommitMs > 0 && prior.lastCommitMs == meal.lastCommitMs) {
                    return Report(Finding.SESSION_LOCK_LOST, index, index)
                }
            }
            previous = meal
        }
        return Report(Finding.OFFLINE_CONTINUITY_DESCRIBED, orderedSnapshots.size)
    }
}
