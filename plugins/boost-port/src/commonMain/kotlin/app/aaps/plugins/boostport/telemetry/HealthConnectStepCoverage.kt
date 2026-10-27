package app.aaps.plugins.boostport.telemetry

/**
 * Read-only KMP port of legacy HealthConnectStepsIngest.chooseSourceByCoverage.
 *
 * Selection uses actual days with positive steps, not the highest raw total.
 * Multiple HC writers MUST remain separate (never sum their totals). This is
 * diagnostic source selection only; it does not query HC or influence dosing.
 */
internal object HealthConnectStepCoverage {
    data class Candidate(
        val source: String,
        val coverageDays: Int,
        val totalSteps: Long,
    )

    data class Resolution(val chosenSource: String?, val sources: List<Candidate>)

    fun resolve(perSourceDay: Map<String, Map<Long, Long>>): Resolution {
        val ranked = perSourceDay.asSequence()
            .filter { it.key.isNotBlank() }
            .map { (source, days) ->
                val positiveCounts = days.values.filter { it > 0L }
                val total = positiveCounts.fold(0L) { acc, value ->
                    if (Long.MAX_VALUE - acc < value) Long.MAX_VALUE else acc + value
                }
                Candidate(source, positiveCounts.size, total)
            }
            .filter { it.coverageDays > 0 }
            .sortedWith(
                compareByDescending<Candidate> { it.coverageDays }
                    .thenByDescending { it.totalSteps }
                    .thenBy { it.source }
            )
            .toList()
        return Resolution(ranked.firstOrNull()?.source, ranked)
    }
}
