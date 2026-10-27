package app.aaps.plugins.aps.openAPSBoost

import kotlin.math.round

/**
 * Read-only port of legacy V1 initial CGM early-exit conditions.
 * Does not emit or schedule the original early-exit basal adjustment.
 */
internal object BoostV1CgmGateShadow {
    enum class Finding { INVALID_INPUT, CGM_UNRELIABLE, STALE, FUTURE, FLAT, USABLE }
    data class Input(
        val currentTimeMs: Long,
        val glucoseTimeMs: Long,
        val bgMgDl: Double,
        val noise: Int,
        val flatBGsDetected: Boolean
    )
    data class Report(
        val finding: Finding,
        val ageMinutes: Double? = null,
        val wouldEnterV1EarlyExit: Boolean = true,
        val canDeliverInsulin: Boolean = false
    )
    fun inspect(i: Input): Report {
        if (i.currentTimeMs <= 0L || i.glucoseTimeMs <= 0L ||
            !i.bgMgDl.isFinite() || i.noise < 0)
            return Report(Finding.INVALID_INPUT)
        // V1 source rounds age to one decimal before comparing against 12 / -5.
        val ageMinutes = round((i.currentTimeMs - i.glucoseTimeMs) / 6000.0) / 10.0
        val finding = when {
            i.bgMgDl <= 10.0 || i.bgMgDl == 38.0 || i.noise >= 3 -> Finding.CGM_UNRELIABLE
            ageMinutes > 12.0 -> Finding.STALE
            ageMinutes < -5.0 -> Finding.FUTURE
            i.bgMgDl > 60.0 && i.flatBGsDetected -> Finding.FLAT
            else -> Finding.USABLE
        }
        return Report(finding, ageMinutes, finding != Finding.USABLE)
    }
}
