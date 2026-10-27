package app.aaps.plugins.aps.openAPSBoostV5

import app.aaps.plugins.boostport.telemetry.BoostV5StateSnapshotCodec

/**
 * Pure, disconnected V5StateStore field bridge. No Preferences, JSON,
 * cache mutation or live APS access. Preserves the commit lock on round trips.
 *
 * In-memory only fields (lastCycleScore, scoreReadySinceMs,
 * mlNullStreakLastMs) are not present in the legacy JSON schema.
 */
internal object V5StateStoreOfflineBridge {
    fun toFields(state: V5PersistedState): Map<String, String>? {
        val meal = state.mealHypothesis
        val snapshot = BoostV5StateSnapshotCodec.Snapshot(
            meal = app.aaps.plugins.boostport.telemetry.BoostV5MealStateDiagnostics.State(
                phase = app.aaps.plugins.boostport.telemetry.BoostV5MealStateDiagnostics.Phase.valueOf(meal.state.name),
                ageCycles = meal.ageCycles,
                peakScore = meal.maxScoreInObserving,
                peakOffset = meal.maxEventualBgOffsetInObserving,
                committedInSession = meal.committedInSession,
                lastAgeMs = meal.lastAgeMs,
                lastCommitMs = meal.lastCommitMs,
                nonPositiveStartMs = meal.nonPositiveRunStartMs,
                peakScoreAtMs = meal.maxScoreAtMs,
                peakOffsetAtMs = meal.maxOffsetAtMs,
            ),
            mlMealLikelyNullStreak = state.mlMealLikelyNullStreak,
            primerAppliedU = state.primerAppliedU,
            primerNettingResidualU = state.primerNettingResidualU,
            primerIobU = state.primerIobU,
            primerIobUpdatedMs = state.primerIobUpdatedMs,
        )
        return BoostV5StateSnapshotCodec.encode(snapshot)
    }

    /** Null rejects corrupt persisted fields rather than clearing the session lock. */
    fun fromFields(fields: Map<String, String>): V5PersistedState? {
        val shape = app.aaps.plugins.boostport.telemetry.BoostV5PersistedStateShapeAudit.inspect(fields)
        if (shape.finding != app.aaps.plugins.boostport.telemetry.BoostV5PersistedStateShapeAudit.Finding.CANONICAL &&
            shape.finding != app.aaps.plugins.boostport.telemetry.BoostV5PersistedStateShapeAudit.Finding.LEGACY_DEFAULTS) return null
        val s = BoostV5StateSnapshotCodec.decode(fields) ?: return null
        val m = s.meal
        return V5PersistedState(
            mealHypothesis = MealHypothesisState(
                state = MealHypothesis.valueOf(m.phase.name),
                ageCycles = m.ageCycles,
                maxScoreInObserving = m.peakScore,
                maxEventualBgOffsetInObserving = m.peakOffset,
                committedInSession = m.committedInSession,
                lastAgeMs = m.lastAgeMs,
                lastCommitMs = m.lastCommitMs,
                nonPositiveRunStartMs = m.nonPositiveStartMs,
                maxScoreAtMs = m.peakScoreAtMs,
                maxOffsetAtMs = m.peakOffsetAtMs,
            ),
            mlMealLikelyNullStreak = s.mlMealLikelyNullStreak,
            primerAppliedU = s.primerAppliedU,
            primerNettingResidualU = s.primerNettingResidualU,
            primerIobU = s.primerIobU,
            primerIobUpdatedMs = s.primerIobUpdatedMs,
        )
    }
}
