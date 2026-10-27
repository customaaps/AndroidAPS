package app.aaps.plugins.boostport.telemetry

/**
 * Disconnected compatibility projection of further original OapsProfileBoost
 * fields. No AAPS profile creation, insulin calculation, or preference access.
 */
internal object BoostV5ProfileAdditionalInputs {
    enum class Finding { VALID, NON_FINITE, INVALID_RANGE, INVALID_INSULIN_PEAK }
    data class Source(
        val variableSensitivity: Double,
        val totalDailyDoseUnits: Double,
        val insulinDivisor: Int,
        val dynamicIsfVelocity: Double,
        val insulinPeakMinutes: Int,
        val boostActive: Boolean,
        val dynamicIsfMode: Boolean,
        val exerciseSubclass: String,
    )
    data class OfflineSnapshot(
        val variableSensitivity: Double,
        val totalDailyDoseUnits: Double,
        val insulinDivisor: Int,
        val dynamicIsfVelocity: Double,
        val insulinPeakMinutes: Int,
        val boostActive: Boolean,
        val dynamicIsfMode: Boolean,
        val exerciseSubclass: String,
    )
    data class Result(val finding: Finding, val snapshot: OfflineSnapshot?)

    fun inspect(source: Source): Result {
        if (listOf(source.variableSensitivity, source.totalDailyDoseUnits, source.dynamicIsfVelocity)
                .any { !it.isFinite() }) return Result(Finding.NON_FINITE, null)
        if (source.variableSensitivity <= 0.0 || source.totalDailyDoseUnits < 0.0 ||
            source.insulinDivisor <= 0 || source.dynamicIsfVelocity < 0.0 ||
            source.dynamicIsfVelocity > 1.0
        ) return Result(Finding.INVALID_RANGE, null)
        // The archived OapsProfileBoost documents peak minutes as 30..75.
        if (source.insulinPeakMinutes !in 30..75)
            return Result(Finding.INVALID_INSULIN_PEAK, null)
        return Result(
            Finding.VALID,
            OfflineSnapshot(
                source.variableSensitivity, source.totalDailyDoseUnits,
                source.insulinDivisor, source.dynamicIsfVelocity,
                source.insulinPeakMinutes, source.boostActive,
                source.dynamicIsfMode, source.exerciseSubclass,
            )
        )
    }
}
