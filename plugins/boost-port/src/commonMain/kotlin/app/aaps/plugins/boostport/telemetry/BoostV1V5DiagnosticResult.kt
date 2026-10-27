package app.aaps.plugins.boostport.telemetry

/**
 * Read-only cross-version session result, not AndroidAPS APSResult or RT.
 * Deliberately excludes dosing decisions, insulin requirements and pump commands.
 */
internal object BoostV1V5DiagnosticResult {
    enum class Finding { INVALID_SESSION, EMPTY_SESSION, INVALID_OBSERVATION, DIAGNOSTIC_ONLY }
    data class Entry(val atMs: Long, val v5Score: Double, val v5MealState: String)
    data class Report(
        val finding: Finding,
        val entries: List<Entry> = emptyList(),
        val canDeliverInsulin: Boolean = false
    )
    fun inspect(session: BoostV1V5GuardedSession.Report): Report {
        if (session.finding != BoostV1V5GuardedSession.Finding.READY)
            return Report(Finding.INVALID_SESSION)
        if (session.observations.isEmpty()) return Report(Finding.EMPTY_SESSION)
        var previous = 0L
        val entries = ArrayList<Entry>(session.observations.size)
        for (o in session.observations) {
            if (o.atMs <= previous || !o.v5Score.isFinite() || o.mealState.isBlank())
                return Report(Finding.INVALID_OBSERVATION)
            entries.add(Entry(o.atMs, o.v5Score, o.mealState))
            previous = o.atMs
        }
        return Report(Finding.DIAGNOSTIC_ONLY, entries)
    }
}
