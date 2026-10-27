package app.aaps.plugins.aps.openAPSBoost

import kotlin.math.floor

/**
 * Diagnostic-only extraction of DetermineBasalBoost.calculate_expected_delta.
 * No profile, pump, APS or treatment output.
 */
internal object BoostV1ExpectedDeltaShadow {
    enum class Finding { READY, INVALID_INPUT }
    data class Report(val finding: Finding, val expectedDelta: Double? = null)

    fun inspect(targetBg: Double, eventualBg: Double, bgi: Double): Report {
        if (!targetBg.isFinite() || !eventualBg.isFinite() || !bgi.isFinite() ||
            targetBg < 0.0 || eventualBg < 0.0
        ) return Report(Finding.INVALID_INPUT)
        val result = bgi + (targetBg - eventualBg) / 24.0
        val scaled = result * 10.0
        if (!scaled.isFinite() || scaled > Long.MAX_VALUE.toDouble() ||
            scaled < Long.MIN_VALUE.toDouble()
        ) return Report(Finding.INVALID_INPUT)
        // Match java.lang.Math.round on ordinary finite values, including negative half-ties.
        val rounded = floor(scaled + 0.5) / 10.0
        return Report(Finding.READY, rounded)
    }
}
