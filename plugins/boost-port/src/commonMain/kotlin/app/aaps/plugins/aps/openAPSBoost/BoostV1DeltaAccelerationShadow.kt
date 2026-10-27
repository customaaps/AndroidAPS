package app.aaps.plugins.aps.openAPSBoost

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

/** Original V1 short-delta acceleration as an offline scalar only. */
internal object BoostV1DeltaAccelerationShadow {
    enum class Finding { INVALID, READY }
    data class Report(val finding: Finding, val percent: Double? = null)
    fun inspect(delta: Double, shortAvgDelta: Double): Report {
        if (!delta.isFinite() || !shortAvgDelta.isFinite()) return Report(Finding.INVALID)
        val percent = if (abs(shortAvgDelta) > 0.001)
            round(100.0 * (delta - shortAvgDelta) / max(abs(shortAvgDelta), 2.0) * 100.0) / 100.0
        else 0.0
        return if (percent.isFinite()) Report(Finding.READY, percent) else Report(Finding.INVALID)
    }
}
