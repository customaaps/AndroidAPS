package app.aaps.plugins.aps.openAPSBoost

import kotlin.math.ln

/**
 * Offline extraction of original DetermineBasalBoost.getIsfByProfile.
 * Never applied to Dmitry profile, correction ordering, dosing or pump.
 */
internal object BoostV1IsfShadowCalculation {
    enum class Finding { READY, INVALID_INPUT }
    data class Input(
        val bgMgDl: Double,
        val capEnabled: Boolean,
        val bgCapMgDl: Double,
        val insulinDivisor: Double,
        val normalTargetMgDl: Double,
        val sensNormalTarget: Double,
        val velocity: Double
    )
    data class Report(val finding: Finding, val shadowIsf: Double? = null)

    fun inspect(i: Input): Report {
        val values = listOf(i.bgMgDl, i.bgCapMgDl, i.insulinDivisor,
            i.normalTargetMgDl, i.sensNormalTarget, i.velocity)
        if (values.any { !it.isFinite() } || i.bgMgDl <= 0.0 ||
            i.insulinDivisor <= 0.0 || i.normalTargetMgDl <= 0.0 ||
            i.sensNormalTarget <= 0.0 || (i.capEnabled && i.bgCapMgDl <= 0.0))
            return Report(Finding.INVALID_INPUT)
        val adjusted = if (i.capEnabled && i.bgMgDl > i.bgCapMgDl)
            i.bgCapMgDl + (i.bgMgDl - i.bgCapMgDl) / 3.0 else i.bgMgDl
        val denominator = ln(adjusted / i.insulinDivisor + 1.0)
        if (!denominator.isFinite() || denominator <= 0.0) return Report(Finding.INVALID_INPUT)
        val scaler = ln(i.normalTargetMgDl / i.insulinDivisor + 1.0) / denominator
        val value = i.sensNormalTarget * (1.0 - (1.0 - scaler) * i.velocity)
        return if (value.isFinite() && value > 0.0) Report(Finding.READY, value)
        else Report(Finding.INVALID_INPUT)
    }
}
