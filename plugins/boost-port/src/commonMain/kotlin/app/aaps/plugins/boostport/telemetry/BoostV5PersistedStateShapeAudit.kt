package app.aaps.plugins.boostport.telemetry

/**
 * Passive schema-compatibility inspection of the legacy V5 persisted state.
 *
 * This checks caller-supplied parsed fields only. It does not read or write
 * Preferences, cache state, load JSON, connect an APS engine or influence
 * any insulin or pump decision.
 */
internal object BoostV5PersistedStateShapeAudit {
    private val required = setOf("mealHypothesis", "mealHypothesisAge")
    private val canonical = setOf(
        "mealHypothesis", "mealHypothesisAge", "mealHypothesisLastAgeMs",
        "maxScoreInObserving", "maxEventualBgOffsetInObserving",
        "committedInSession", "lastCommitMs", "nonPositiveRunStartMs",
        "maxScoreAtMs", "maxOffsetAtMs", "mlMealLikelyNullStreak",
        "primerAppliedU", "primerNettingResidualU", "primerIobU",
        "primerIobUpdatedMs",
    )
    private val cacheOnly = setOf("lastCycleScore")

    enum class Finding {
        MISSING_REQUIRED, MALFORMED, CACHE_ONLY_FIELD, FUTURE_FIELDS,
        LEGACY_DEFAULTS, CANONICAL
    }

    data class Report(
        val finding: Finding,
        val missingLegacyFields: Set<String>,
        val unknownFields: Set<String>,
    )

    fun inspect(fields: Map<String, String>): Report {
        val missing = canonical - fields.keys
        val unknown = fields.keys - canonical
        if (!fields.keys.containsAll(required))
            return Report(Finding.MISSING_REQUIRED, missing, unknown)
        if (fields.keys.any { it in cacheOnly })
            return Report(Finding.CACHE_ONLY_FIELD, missing, unknown)
        if (BoostV5StateSnapshotCodec.decode(fields) == null)
            return Report(Finding.MALFORMED, missing, unknown)
        // Do not silently claim parity when newer versions add data that the
        // current snapshot codec would discard during a round trip.
        if (unknown.isNotEmpty())
            return Report(Finding.FUTURE_FIELDS, missing, unknown)
        if (missing.isNotEmpty())
            return Report(Finding.LEGACY_DEFAULTS, missing, emptySet())
        return Report(Finding.CANONICAL, emptySet(), emptySet())
    }
}
