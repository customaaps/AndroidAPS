package app.aaps.plugins.boostport.telemetry

/**
 * KMP, read-only adaptation of V5StateStore's persisted *field schema*.
 *
 * Uses an immutable string-value map as an intermediate representation, not
 * JSON or Android preferences. A future platform JSON adapter must be
 * reviewed separately. This code neither loads nor saves any AAPS state.
 *
 * Historical fields are retained by exact name, including the 2026-10-08
 * lock/peak clocks. A missing old field receives the source-compatible
 * default; a malformed value rejects the whole snapshot instead of
 * silently weakening the session lock. The cache-only lastCycleScore is
 * deliberately absent from this representation.
 */
internal object BoostV5StateSnapshotCodec {
    data class Snapshot(
        val meal: BoostV5MealStateDiagnostics.State = BoostV5MealStateDiagnostics.State(),
        val mlMealLikelyNullStreak: Int = 0,
        val primerAppliedU: Double = 0.0,
        val primerNettingResidualU: Double = 0.0,
        val primerIobU: Double = 0.0,
        val primerIobUpdatedMs: Long = 0L,
    )

    /** A portable, immutable copy of the source's JSON key/value schema. */
    fun encode(snapshot: Snapshot): Map<String, String>? {
        if (!valid(snapshot)) return null
        val s = snapshot.meal
        return mapOf(
            "mealHypothesis" to s.phase.name,
            "mealHypothesisAge" to s.ageCycles.toString(),
            "mealHypothesisLastAgeMs" to s.lastAgeMs.toString(),
            "maxScoreInObserving" to s.peakScore.toString(),
            "maxEventualBgOffsetInObserving" to s.peakOffset.toString(),
            "committedInSession" to s.committedInSession.toString(),
            "lastCommitMs" to s.lastCommitMs.toString(),
            "nonPositiveRunStartMs" to s.nonPositiveStartMs.toString(),
            "maxScoreAtMs" to s.peakScoreAtMs.toString(),
            "maxOffsetAtMs" to s.peakOffsetAtMs.toString(),
            "mlMealLikelyNullStreak" to snapshot.mlMealLikelyNullStreak.toString(),
            "primerAppliedU" to snapshot.primerAppliedU.toString(),
            "primerNettingResidualU" to snapshot.primerNettingResidualU.toString(),
            "primerIobU" to snapshot.primerIobU.toString(),
            "primerIobUpdatedMs" to snapshot.primerIobUpdatedMs.toString(),
        )
    }

    /**
     * No side effects. Null means corrupt/incomplete required data, and
     * therefore no diagnostic replay should be resumed from that snapshot.
     */
    fun decode(fields: Map<String, String>): Snapshot? {
        val phase = BoostV5MealStateDiagnostics.Phase.entries.firstOrNull {
            it.name == fields["mealHypothesis"]
        } ?: return null
        val age = fields["mealHypothesisAge"]?.toIntOrNull() ?: return null

        fun optionalInt(key: String, default: Int = 0): Int? =
            fields[key]?.toIntOrNull() ?: if (key !in fields) default else null
        fun optionalLong(key: String): Long? =
            fields[key]?.toLongOrNull() ?: if (key !in fields) 0L else null
        fun optionalDouble(key: String): Double? =
            fields[key]?.toDoubleOrNull() ?: if (key !in fields) 0.0 else null
        val locked = fields["committedInSession"]?.toBooleanStrictOrNull()
            ?: if ("committedInSession" !in fields) false else return null

        val meal = BoostV5MealStateDiagnostics.State(
            phase = phase,
            ageCycles = age,
            peakScore = optionalDouble("maxScoreInObserving") ?: return null,
            peakOffset = optionalDouble("maxEventualBgOffsetInObserving") ?: return null,
            committedInSession = locked,
            lastAgeMs = optionalLong("mealHypothesisLastAgeMs") ?: return null,
            lastCommitMs = optionalLong("lastCommitMs") ?: return null,
            nonPositiveStartMs = optionalLong("nonPositiveRunStartMs") ?: return null,
            peakScoreAtMs = optionalLong("maxScoreAtMs") ?: return null,
            peakOffsetAtMs = optionalLong("maxOffsetAtMs") ?: return null,
        )
        val restored = Snapshot(
            meal = meal,
            mlMealLikelyNullStreak = optionalInt("mlMealLikelyNullStreak") ?: return null,
            primerAppliedU = optionalDouble("primerAppliedU") ?: return null,
            primerNettingResidualU = optionalDouble("primerNettingResidualU") ?: return null,
            primerIobU = optionalDouble("primerIobU") ?: return null,
            primerIobUpdatedMs = optionalLong("primerIobUpdatedMs") ?: return null,
        )
        return restored.takeIf(::valid)
    }

    private fun valid(snapshot: Snapshot): Boolean {
        val s = snapshot.meal
        return s.ageCycles >= 0 &&
            s.lastAgeMs >= 0L && s.lastCommitMs >= 0L &&
            s.nonPositiveStartMs >= 0L && s.peakScoreAtMs >= 0L &&
            s.peakOffsetAtMs >= 0L &&
            s.peakScore.isFinite() && s.peakOffset.isFinite() &&
            snapshot.mlMealLikelyNullStreak >= 0 &&
            snapshot.primerAppliedU.isFinite() && snapshot.primerAppliedU >= 0.0 &&
            snapshot.primerNettingResidualU.isFinite() &&
            snapshot.primerIobU.isFinite() && snapshot.primerIobU >= 0.0 &&
            snapshot.primerIobUpdatedMs >= 0L
    }
}
