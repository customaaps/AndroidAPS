package app.aaps.plugins.aps.openAPSBoost.replay

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.plugins.aps.openAPSBoost.BoostV1ReplayEngine

/** Complete recorded V1 input, assembled by the caller without app-service reads. */
internal data class V1ReplayCycle(
    val glucose: GlucoseStatus,
    val currentTemp: CurrentTemp,
    val iob: Array<IobTotal>,
    val profile: V1ReplayProfile,
    val autosens: AutosensResult,
    val meal: MealData,
    val microBolusAllowed: Boolean,
    val time: Long,
    val flatBGsDetected: Boolean,
    val recentSmbVolume60Min: Double,
    val cumulativeSmbCap60Min: Double,
    val recentLowBG45Min: Double,
    val timeSinceLastSmbMin: Double,
    val postRescueTightRampCap: Double?,
) {
    fun calculate(
        engine: BoostV1ReplayEngine,
        risk: V1ReplayRiskModel?,
        mealModel: V1ReplayMealModel?,
    ): V1ReplayResult = engine.determine_basal(
        glucose_status = glucose, currenttemp = currentTemp.copy(), iob_data_array = iob,
        profile = profile.copy(), autosens_data = autosens.copy(), meal_data = meal.copy(),
        microBolusAllowed = microBolusAllowed, currentTime = time, flatBGsDetected = flatBGsDetected,
        riskModel = risk, mealModel = mealModel, recentSmbVolume60Min = recentSmbVolume60Min,
        cumulativeSmbCap60Min = cumulativeSmbCap60Min, recentLowBG45Min = recentLowBG45Min,
        timeSinceLastSmbMin = timeSinceLastSmbMin, postRescueTightRampCap = postRescueTightRampCap,
    )
}
