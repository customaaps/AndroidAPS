package app.aaps.ui.compose.main

import app.aaps.core.interfaces.overview.graph.TbrDisplayData

/** Display conversion only. Never changes the stored or delivered temporary basal. */
internal data class TbrDisplayValue(val value: Double, val isPercent: Boolean)

internal fun TbrDisplayData.displayValue(asPercent: Boolean): TbrDisplayValue? {
    if (!isRunning) return null
    val rate = absoluteRate?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
    val basal = profileRate?.takeIf { it.isFinite() && it > 0.0 }
    if (asPercent && basal != null) {
        val percent = rate / basal * 100.0
        if (percent.isFinite()) return TbrDisplayValue(percent, true)
    }
    return TbrDisplayValue(rate, false)
}
