package app.aaps.plugins.boostport.telemetry

/**
 * Explicit caller-fed comparison of recorded V1/V5/V6 *offline* outputs.
 * This class never invokes an APS engine, returns no actionable dose,
 * and has no access to a pump, database, preferences or Android services.
 */
internal object BoostOfflineEngineComparison {
    enum class Finding {
        EMPTY, INVALID_TIMESTAMP, NON_MONOTONIC_TIME, INVALID_INPUT,
        MISSING_REFERENCE, DIVERGENT, MATCHED
    }
    data class Sample(
        val atMs: Long,
        val referenceUnits: Double?,
        val candidateUnits: Double?,
        val referenceTempBasalRate: Double?,
        val candidateTempBasalRate: Double?,
    )
    data class Report(
        val finding: Finding,
        val firstProblemIndex: Int? = null,
        val compared: Int = 0,
        val divergent: Int = 0,
    )

    /** Strictly diagnostic thresholds, never dose acceptance criteria. */
    fun compare(samples: List<Sample>, reportingTolerance: Double = 0.000001): Report {
        if (samples.isEmpty()) return Report(Finding.EMPTY)
        if (!reportingTolerance.isFinite() || reportingTolerance < 0.0)
            return Report(Finding.INVALID_INPUT)
        var count = 0
        var divergent = 0
        var firstMismatch: Int? = null
        for ((index, sample) in samples.withIndex()) {
            if (sample.atMs < 0) return Report(Finding.INVALID_TIMESTAMP, index, count, divergent)
            if (index > 0 && sample.atMs <= samples[index - 1].atMs)
                return Report(Finding.NON_MONOTONIC_TIME, index, count, divergent)
            val values = listOf(sample.referenceUnits, sample.candidateUnits,
                sample.referenceTempBasalRate, sample.candidateTempBasalRate)
            if (values.any { it != null && (!it.isFinite() || it < 0.0) })
                return Report(Finding.INVALID_INPUT, index, count, divergent)
            if (values.any { it == null })
                return Report(Finding.MISSING_REFERENCE, index, count, divergent)
            count++
            val mismatch = kotlin.math.abs(sample.referenceUnits!! - sample.candidateUnits!!) > reportingTolerance ||
                kotlin.math.abs(sample.referenceTempBasalRate!! - sample.candidateTempBasalRate!!) > reportingTolerance
            if (mismatch) {
                divergent++
                if (firstMismatch == null) firstMismatch = index
            }
        }
        return Report(if (divergent == 0) Finding.MATCHED else Finding.DIVERGENT,
            firstMismatch, count, divergent)
    }
}
