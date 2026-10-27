package app.aaps.plugins.aps.openAPSBoost

import kotlin.math.max
import kotlin.math.round

/**
 * Isolated, read-only migration of the original V1 high-BG target adjustment
 * branch. Original DetermineBasalBoost.kt, lines 574–595.
 * No treatment target is changed in AndroidAPS; all values are local snapshots.
 */
internal object BoostV1HighBgTargetShadow {
    data class Input(
        val bg: Double,
        val minBg: Double,
        val targetBg: Double,
        val maxBg: Double,
        val eventualBg: Double,
        val naiveEventualBg: Double,
        val advancedTargetAdjustments: Boolean,
        val tempTargetSet: Boolean
    )
    enum class Finding { INVALID_INPUT, NO_ADJUSTMENT, ADJUSTED }
    data class Report(
        val finding: Finding,
        val minBg: Double?,
        val targetBg: Double?,
        val maxBg: Double?,
        val minChanged: Boolean = false,
        val targetChanged: Boolean = false,
        val maxChanged: Boolean = false,
        val canDeliverInsulin: Boolean = false
    )
    fun inspect(i: Input): Report {
        if (listOf(i.bg, i.minBg, i.targetBg, i.maxBg, i.eventualBg,
                i.naiveEventualBg).any { !it.isFinite() } ||
            i.bg <= 0 || i.minBg <= 0 || i.targetBg <= 0 || i.maxBg <= 0 ||
            i.minBg > i.targetBg || i.targetBg > i.maxBg
        ) return Report(Finding.INVALID_INPUT, null, null, null)
        if (i.bg <= i.maxBg || !i.advancedTargetAdjustments || i.tempTargetSet)
            return Report(Finding.NO_ADJUSTMENT, i.minBg, i.targetBg, i.maxBg)
        fun adjusted(value: Double) = round(max(80.0, value - (i.bg - value) / 3.0))
        val aMin = adjusted(i.minBg)
        val aTarget = adjusted(i.targetBg)
        val aMax = adjusted(i.maxBg)
        fun shouldAdjust(original: Double, candidate: Double) =
            i.eventualBg > candidate && i.naiveEventualBg > candidate && original > candidate
        val useMin = shouldAdjust(i.minBg, aMin)
        val useTarget = shouldAdjust(i.targetBg, aTarget)
        val useMax = shouldAdjust(i.maxBg, aMax)
        return Report(
            if (useMin || useTarget || useMax) Finding.ADJUSTED else Finding.NO_ADJUSTMENT,
            if (useMin) aMin else i.minBg,
            if (useTarget) aTarget else i.targetBg,
            if (useMax) aMax else i.maxBg,
            useMin, useTarget, useMax
        )
    }
}
