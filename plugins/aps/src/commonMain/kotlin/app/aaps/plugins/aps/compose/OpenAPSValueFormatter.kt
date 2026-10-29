package app.aaps.plugins.aps.compose

import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.format.NumberFormat
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import kotlin.math.abs
import kotlin.math.pow

/** Presentation only: APS inputs/results remain in their original units and precision. */
internal class OpenAPSValueFormatter(
    private val rh: TextResolver,
    private val glucoseUnit: () -> GlucoseUnit
) {
    fun format(key: String, value: Number): String {
        val raw = value.toDouble()
        if (!raw.isFinite()) return rh.gs(CoreUiStrings.not_available_full)

        return when (key) {
            "glucose", "bg", "min_bg", "max_bg", "target_bg", "eventualBG", "targetBG", "snoozeBG",
            "delta", "shortAvgDelta", "longAvgDelta", "half_basal_exercise_target", "lgsThreshold",
            "min_5m_carbimpact", "usedMinCarbsImpact", "smb_delivery_ratio_bg_range",
            "slopeFromMaxDeviation", "slopeFromMinDeviation" -> {
                val units = glucoseUnit()
                withUnit(
                    number(inGlucoseUnits(raw, units), if (units == GlucoseUnit.MGDL) 0 else 1),
                    if (units == GlucoseUnit.MGDL) CoreUiStrings.units_mgdl else CoreUiStrings.units_mmol
                )
            }

            "sens", "variable_sens", "isfMgdlForCarbs" -> {
                val units = glucoseUnit()
                withUnit(
                    number(inGlucoseUnits(raw, units), 2),
                    if (units == GlucoseUnit.MGDL) InterfacesStrings.profile_isf_units_mgdl
                    else InterfacesStrings.profile_isf_units_mmol
                )
            }

            "iob", "IOB", "bolussnooze", "basaliob", "netbasalinsulin", "hightempinsulin",
            "netInsulin", "extendedBolusInsulin", "max_iob", "insulinReq", "units",
            "bolus_increment", "TDD" -> withUnit(number(raw, 3), CoreUiStrings.units_insulin)

            "rate", "current_basal", "max_daily_basal", "max_basal" ->
                withUnit(number(raw, 3), CoreUiStrings.units_insulin_rate)

            "carb_ratio" -> withUnit(number(raw, 2), InterfacesStrings.profile_carbs_per_unit)

            "carbs", "mealCOB", "COB", "carbsAbsorbed", "carbsReq", "carbsReqThreshold",
            "remainingCarbsCap", "maxCOB" -> withUnit(number(raw, 1), CoreUiStrings.units_grams)

            "duration", "minutesrunning", "SMBInterval", "maxSMBBasalMinutes", "maxUAMSMBBasalMinutes",
            "carbsReqWithin" -> withUnit(number(raw, 0), CoreUiStrings.units_min)

            "dia" -> withUnit(number(raw, 2), CoreUiStrings.units_hours)
            "profile_percentage", "iob_threshold_percent" ->
                withUnit(number(raw, 0), CoreUiStrings.units_percent)

            // Activity is much smaller than IOB; do not round it away as if it were a dose.
            "activity" -> number(raw, 6)
            else -> number(raw, 3)
        }
    }

    private fun inGlucoseUnits(value: Double, units: GlucoseUnit): Double =
        if (units == GlucoseUnit.MGDL) value else value * Constants.MGDL_TO_MMOLL

    private fun withUnit(value: String, unit: TextRef): String =
        rh.gs(CoreUiStrings.value_with_unit, value, rh.gs(unit))

    private fun number(value: Double, decimals: Int): String {
        // Avoid a displayed "-0" after rounding a tiny negative diagnostic value.
        val normalized = if (abs(value) < 0.5 * 10.0.pow(-decimals)) 0.0 else value
        return NumberFormat(minFractionDigits = 0, maxFractionDigits = decimals).format(normalized)
    }
}
