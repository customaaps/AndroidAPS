package app.aaps.plugins.boostport.telemetry

/**
 * Strict, disconnected input boundary for a subset of OapsProfileBoost fields.
 * This is NOT a dose engine or conversion to a live AAPS profile. It never
 * calculates insulin, invokes APS, or accesses pump/state/preferences APIs.
 *
 * Units are explicit to prevent mixing mmol/L glucose with mg/dL signals.
 */
internal object BoostV5ProfileInputBoundary {
    enum class Finding { VALID, INVALID_NUMBER, INVALID_RANGE, INCONSISTENT_TARGET, INVALID_STEPS }
    data class Source(
        val minBgMgdl: Double,
        val targetBgMgdl: Double,
        val maxBgMgdl: Double,
        val currentBasalUnitsPerHour: Double,
        val recentLowBgMgdl: Double,
        val steps5Min: Int,
        val steps15Min: Int,
        val steps30Min: Int,
        val steps60Min: Int,
        val exerciseActive: Boolean,
        val inPostExerciseWindow: Boolean,
    )
    data class OfflineSnapshot(
        val minBgMgdl: Double,
        val targetBgMgdl: Double,
        val maxBgMgdl: Double,
        val currentBasalUnitsPerHour: Double,
        val recentLowBgMgdl: Double,
        val steps: List<Int>,
        val exerciseActive: Boolean,
        val inPostExerciseWindow: Boolean,
    )
    data class Result(val finding: Finding, val snapshot: OfflineSnapshot?)

    fun inspect(source: Source): Result {
        val numbers = listOf(
            source.minBgMgdl, source.targetBgMgdl, source.maxBgMgdl,
            source.currentBasalUnitsPerHour, source.recentLowBgMgdl,
        )
        if (numbers.any { !it.isFinite() }) return Result(Finding.INVALID_NUMBER, null)
        // Basic input sanity only; these are not clinical safety thresholds.
        if (source.minBgMgdl <= 0.0 || source.maxBgMgdl <= 0.0 ||
            source.recentLowBgMgdl <= 0.0 || source.currentBasalUnitsPerHour < 0.0
        ) return Result(Finding.INVALID_RANGE, null)
        if (source.minBgMgdl > source.targetBgMgdl ||
            source.targetBgMgdl > source.maxBgMgdl
        ) return Result(Finding.INCONSISTENT_TARGET, null)
        val steps = listOf(source.steps5Min, source.steps15Min, source.steps30Min, source.steps60Min)
        if (steps.any { it < 0 } || steps.zipWithNext().any { (small, large) -> small > large }) {
            return Result(Finding.INVALID_STEPS, null)
        }
        return Result(
            Finding.VALID,
            OfflineSnapshot(
                source.minBgMgdl, source.targetBgMgdl, source.maxBgMgdl,
                source.currentBasalUnitsPerHour, source.recentLowBgMgdl,
                steps, source.exerciseActive, source.inPostExerciseWindow,
            )
        )
    }
}
