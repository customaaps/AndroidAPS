// Complete source ISF preparation; explicit inputs, no app-service mutation.
package app.aaps.plugins.aps.openAPSBoost.replay

import app.aaps.core.interfaces.utils.Round
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.max

internal object BoostIsfPreparation {
    fun calculate(input: BoostIsfInputs, settings: BoostIsfSettings, parts: BoostTddParts): BoostIsfPreparationResult {
        val profileSens = input.profileSens
        val profilePercent = input.profilePercent
        val targetBg = input.targetBg
        val insulinDivisor = input.insulinDivisor
        val glucoseValue = input.glucoseValue
        val isTempTarget = input.isTempTarget
        val orefAutosensRatio = input.orefAutosensRatio
        val autosensMax = settings.autosensMax
        val autosensMin = settings.autosensMin
        val useTdd = settings.useTdd
        val velocity = if (useTdd) settings.velocity else 0.0
        val bgCap = settings.bgCap
        val bgNormalTarget = settings.normalTarget
        val highTtRaisesSens = settings.highTtRaisesSens
        val lowTtLowersSens = settings.lowTtLowersSens
        val halfBasalTarget = settings.halfBasalTarget

        val globalScale = 100.0 / profilePercent.toDouble()

        var sensNormalTarget = profileSens
        var ratio = 1.0
        // ISF shadow accumulator — populated when V4.4.2-style EMA ratio is computed below.
        var tdd = 0.0
        val bgCurrent = if (glucoseValue > bgCap) bgCap + ((glucoseValue - bgCap) / 3.0) else glucoseValue

        val debug = StringBuilder()

        // TDD-based ISF calculation
        val adjustSens = useTdd && settings.adjustSensitivity

        if (useTdd) {
            // Fetch all TDD components — use allowMissingDays=true so partial data still works
            val tdd7D = parts.average7Days
            val tdd1D = parts.average1Day
            val tddLast24H = parts.last24Hours
            val tddLast4H = parts.last4Hours
            val tddLast8to4H = parts.last8To4Hours

            debug.append("TDD data: 7D=${tdd7D?.let { Round.roundTo(it, 0.1) } ?: "null"}")
            debug.append(" | 1D=${tdd1D?.let { Round.roundTo(it, 0.1) } ?: "null"}")
            debug.append(" | 24H=${tddLast24H?.let { Round.roundTo(it, 0.1) } ?: "null"}")
            debug.append(" | 4H=${tddLast4H?.let { Round.roundTo(it, 0.1) } ?: "null"}")
            debug.append(" | 8-4H=${tddLast8to4H?.let { Round.roundTo(it, 0.1) } ?: "null"}")

            // Require ALL critical components — same safety gate as standard DynISF
            if (tdd7D != null && tdd1D != null && tddLast24H != null && tddLast4H != null && tddLast8to4H != null && tdd7D > 0) {
                val tddWeightedFromLast8H = ((1.4 * tddLast4H) + (0.6 * tddLast8to4H)) * 3
                debug.append("\nWeighted8H=${Round.roundTo(tddWeightedFromLast8H, 0.1)} (4H×1.4 + 8-4H×0.6)×3")

                if (tddWeightedFromLast8H < (0.75 * tdd7D)) {
                    // Recent insulin usage significantly below 7D average —
                    // pull the 7D average down toward recent reality before blending
                    val adjusted7D = tddWeightedFromLast8H + ((tddWeightedFromLast8H / tdd7D) * (tdd7D - tddWeightedFromLast8H))
                    tdd = (adjusted7D * 0.34) + (tdd1D * 0.33) + (tddWeightedFromLast8H * 0.33)
                    debug.append("\nW8H < 75% of 7D → adjusted7D=${Round.roundTo(adjusted7D, 0.1)} (7D ${Round.roundTo(tdd7D, 0.1)} pulled toward W8H)")
                } else {
                    // Standard blend
                    tdd = (tddWeightedFromLast8H * 0.33) + (tdd7D * 0.34) + (tdd1D * 0.33)
                    debug.append("\nStandard blend (W8H×.33 + 7D×.34 + 1D×.33)")
                }
                debug.append("\nBlended TDD=${Round.roundTo(tdd, 0.1)}")

                // Adjustment factor from Boost DynISF preferences (default 100%)
                val dynIsfAdjust = settings.adjustmentFactor.toDouble().coerceIn(1.0, 300.0)
                tdd *= dynIsfAdjust / 100.0
                debug.append("\nFinal TDD=${Round.roundTo(tdd, 0.1)} (adj factor ${dynIsfAdjust.toInt()}%)")

                // Safety: TDD must be positive and produce a sane ISF
                val logTerm = ln((bgNormalTarget / insulinDivisor) + 1.0)
                if (tdd > 0 && logTerm > 0) {
                    sensNormalTarget = 1800.0 / (tdd * logTerm)
                    sensNormalTarget *= globalScale
                    debug.append("\nTDD ISF at target: ${Round.roundTo(sensNormalTarget, 0.1)} mg/dl/U (profile was ${Round.roundTo(profileSens, 0.1)})")

                    if (adjustSens && tddLast24H > 0) {
                        ratio = max(min(tddLast24H / tdd7D, autosensMax), autosensMin)
                        sensNormalTarget /= ratio
                        debug.append("\nSens ratio: ${Round.roundTo(ratio, 0.01)} (24H/7D = ${Round.roundTo(tddLast24H, 0.1)}/${Round.roundTo(tdd7D, 0.1)}) → ISF=${Round.roundTo(sensNormalTarget, 0.1)}")
                    }

                } else {
                    debug.append("\n⚠ TDD calculation produced invalid values (tdd=$tdd, logTerm=$logTerm) — using profile ISF")
                }
            } else {
                debug.append("\n⚠ TDD data incomplete — using profile ISF")
            }
        } else {
            debug.append("TDD-based ISF: disabled (using profile ISF ${Round.roundTo(profileSens, 0.1)})")
        }

        // Temp target sensitivity adjustment
        if (isTempTarget && ((highTtRaisesSens && targetBg > bgNormalTarget) || (lowTtLowersSens && targetBg < bgNormalTarget))) {
            val c = (halfBasalTarget - bgNormalTarget).toDouble()
            if (c * (c + targetBg - bgNormalTarget) > 0.0) {
                ratio = c / (c + targetBg - bgNormalTarget)
                ratio = max(min(ratio, autosensMax), autosensMin)
                sensNormalTarget /= ratio
                debug.append("\nTT adjustment: ratio=${Round.roundTo(ratio, 0.01)} → ISF=${Round.roundTo(sensNormalTarget, 0.1)}")
            }
        }

        // This engine builds prediction and dosing ISF from sensNormalTarget; adjust here,
        // instead of altering the basal/target autosens ratio used elsewhere.
        val autosensSens = autosensAdjustedIsf(
            sensNormalTarget, useTdd,
            settings.autosensWhenNoTdd, ratio, orefAutosensRatio
        )
        if (autosensSens != sensNormalTarget) {
            debug.append("\nAutosens: ISF at target adjusted from ${Round.roundTo(sensNormalTarget, 0.1)} to ${Round.roundTo(autosensSens, 0.1)}")
            sensNormalTarget = autosensSens
        }

        // Calculate variable_sens using log formula
        val sbg = ln((bgCurrent / insulinDivisor) + 1.0)
        val scaler = ln((bgNormalTarget / insulinDivisor) + 1.0) / sbg
        val variableSens = sensNormalTarget * (1 - (1 - scaler) * velocity)

        debug.append("\nVariable ISF at BG ${Round.roundTo(glucoseValue, 1.0)}: ${Round.roundTo(variableSens, 0.1)} (velocity=${Round.roundTo(velocity * 100, 1.0)}%)")


        return BoostIsfPreparationResult(
            sensNormalTarget = Round.roundTo(sensNormalTarget, 0.1),
            variableSens = Round.roundTo(variableSens, 0.1),
            bgCapped = bgCurrent,
            insulinDivisor = insulinDivisor,
            dynIsfVelocity = velocity,
            ratio = Round.roundTo(ratio, 0.01),
            tdd = tdd,
            isfDebug = debug.toString(),
        )
    }


    internal fun autosensAdjustedIsf(sensNormalTarget: Double, useTdd: Boolean, autosensWhenNoTdd: Boolean, tempTargetRatio: Double, orefAutosensRatio: Double): Double =
        if (useTdd || !autosensWhenNoTdd || tempTargetRatio != 1.0 || !orefAutosensRatio.isFinite() || orefAutosensRatio <= 0.0) sensNormalTarget
        else sensNormalTarget / orefAutosensRatio

    internal fun selectSensitivityRatio(
        useTdd: Boolean,
        autosensWhenNoTdd: Boolean,
        isfResultRatio: Double,
        orefAutosensRatio: Double
    ): Double = when {
        useTdd            -> isfResultRatio
        autosensWhenNoTdd -> orefAutosensRatio
        else              -> isfResultRatio
    }

}
