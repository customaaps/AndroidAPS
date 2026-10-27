package app.aaps.plugins.aps.openAPSBoost

/**
 * Isolated port of DetermineBasalBoost.postRescueReboundScale from the
 * SHA-verified V6 source. This computes only a diagnostic multiplier:
 * it never selects or delivers an insulin dose.
 *
 * Nonfinite inputs are rejected at this boundary instead of propagating a
 * potentially unsafe result. In-domain expression is unchanged.
 */
internal object BoostV1PostRescueShadow {
    enum class Finding { INVALID_BG, READY }
    data class Result(val finding: Finding, val reboundScale: Double? = null)

    fun inspect(bgMgDl: Double): Result {
        if (!bgMgDl.isFinite() || bgMgDl < 0.0) return Result(Finding.INVALID_BG)
        return Result(Finding.READY, when {
            bgMgDl < 120.0 -> 0.3
            bgMgDl < 170.0 -> 0.3 + 0.7 * (bgMgDl - 120.0) / 50.0
            else -> 1.0
        })
    }
}
