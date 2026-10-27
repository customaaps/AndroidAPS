package app.aaps.plugins.aps.openAPSBoost

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Offline extraction of DetermineBasalBoost.kt lines 1039–1048, following
 * BoostV1PredictionSafetyTimeline. These are comparison values only: no RT,
 * notification, treatment entry or pump action is created.
 */
internal object BoostV1CarbRequirementShadow {
    data class Input(
        val currentBasal: Double,
        val sensitivity: Double,
        val profileSensitivity: Double,
        val carbRatio: Double,
        val dynamicCarbRatio: Double,
        val mealCarbs: Double,
        val mealCob: Double,
        val carbsReqThreshold: Double
    )

    enum class Finding { INVALID_INPUT, INVALID_SAFETY_TIMELINE, READY }

    data class Result(
        val finding: Finding,
        val effectiveCarbRatio: Double? = null,
        val carbSensitivityFactor: Double? = null,
        val zeroTempEffectMgDl: Double? = null,
        val cobOffsetGrams: Double? = null,
        val calculatedCarbsGrams: Int? = null,
        val wouldReportCarbsRequired: Boolean = false,
        val reportedWithinMinutes: Int? = null,
        val canDeliverInsulin: Boolean = false
    )

    fun inspect(i: Input, safety: BoostV1PredictionSafetyTimeline.Result): Result {
        val values = listOf(i.currentBasal, i.sensitivity, i.profileSensitivity,
            i.carbRatio, i.dynamicCarbRatio, i.mealCarbs, i.mealCob, i.carbsReqThreshold)
        if (values.any { !it.isFinite() } || i.currentBasal < 0.0 ||
            i.sensitivity <= 0.0 || i.profileSensitivity <= 0.0 ||
            i.carbRatio <= 0.0 || i.dynamicCarbRatio < 0.0 ||
            i.mealCarbs < 0.0 || i.mealCob < 0.0 || i.carbsReqThreshold < 0.0)
            return Result(Finding.INVALID_INPUT)
        val minutes = safety.minutesAboveThreshold
        val undershoot = safety.glucoseUndershoot
        if (safety.finding != BoostV1PredictionSafetyTimeline.Finding.READY ||
            minutes == null || minutes !in 0..240 || minutes % 5 != 0 ||
            undershoot == null || !undershoot.isFinite())
            return Result(Finding.INVALID_SAFETY_TIMELINE)

        val ratio = if (i.dynamicCarbRatio > 0.0) i.dynamicCarbRatio else i.carbRatio
        // The source uses original profile sens for CSF, not autosens/dynamic ISF.
        val csf = i.profileSensitivity / ratio
        val zeroTempEffect = i.currentBasal * i.sensitivity * minutes / 60.0
        val cobOffset = max(0.0, i.mealCob - 0.25 * i.mealCarbs)
        val carbs = (undershoot - zeroTempEffect) / csf - cobOffset
        if (!csf.isFinite() || csf <= 0.0 || !zeroTempEffect.isFinite() ||
            !cobOffset.isFinite() || !carbs.isFinite() ||
            carbs < Int.MIN_VALUE.toDouble() || carbs > Int.MAX_VALUE.toDouble())
            return Result(Finding.INVALID_INPUT)
        // Source round(value) is roundToInt(), including negative half ties.
        // Negative values stay diagnostic values; do not clamp to zero.
        val rounded = carbs.roundToInt()
        val report = rounded >= i.carbsReqThreshold && minutes <= 45
        return Result(
            finding = Finding.READY,
            effectiveCarbRatio = ratio,
            carbSensitivityFactor = csf,
            zeroTempEffectMgDl = zeroTempEffect,
            cobOffsetGrams = cobOffset,
            calculatedCarbsGrams = rounded,
            wouldReportCarbsRequired = report,
            reportedWithinMinutes = if (report) minutes else null
        )
    }
}
