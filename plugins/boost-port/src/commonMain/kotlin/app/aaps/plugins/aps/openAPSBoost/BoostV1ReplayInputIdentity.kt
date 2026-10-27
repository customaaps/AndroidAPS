package app.aaps.plugins.aps.openAPSBoost

/**
 * Canonical, deterministic replay identity payload. This is NOT a cryptographic
 * fingerprint; consumers must hash the UTF-8 bytes with a cryptographic digest
 * before using the value as recorded-input provenance.
 *
 * No rounding, locale formatting, mutable AAPS references, or IOB sorting.
 */
internal object BoostV1ReplayInputIdentity {
    fun canonical(input: BoostV1DetermineBasalInvocation.Inputs): String = buildString {
        val base = input.base
        fun scalar(name: String, value: Any?) {
            append(name).append('=').append(value?.toString() ?: "null").append('\n')
        }
        scalar("schema", 1)
        scalar("time", base.glucose.currentTimeMs)
        scalar("glucoseTime", base.glucose.glucoseTimeMs)
        scalar("bgMgDl", base.glucose.bgMgDl.toBits())
        scalar("noise", base.glucose.noise)
        scalar("delta", base.glucoseDynamics?.delta?.toBits())
        scalar("shortAvgDelta", base.glucoseDynamics?.shortAvgDelta?.toBits())
        scalar("longAvgDelta", base.glucoseDynamics?.longAvgDelta?.toBits())
        scalar("bgAcceleration", base.glucoseDynamics?.bgAcceleration?.toBits())
        scalar("flat", base.glucose.flatBGsDetected)
        scalar("currentTempRate", base.currentTempRate.toBits())
        scalar("currentTempMinutes", base.currentTempMinutes)
        scalar("currentBasal", base.profile.currentBasal.toBits())
        scalar("maxBasal", base.profile.maxBasal.toBits())
        scalar("maxDailyBasal", base.profile.maxDailyBasal.toBits())
        scalar("maxDailyMultiplier", base.profile.maxDailySafetyMultiplier.toBits())
        scalar("currentMultiplier", base.profile.currentBasalSafetyMultiplier.toBits())
        scalar("skipNeutral", base.profile.skipNeutralTemps)
        scalar("iobCount", base.iob.size)
        base.iob.forEachIndexed { index, point ->
            scalar("iob[${index}].time", point.timeMs)
            scalar("iob[${index}].iob", point.iob.toBits())
            scalar("iob[${index}].activity", point.activity.toBits())
        }
        scalar("autosensRatio", input.autosens.ratio.toBits())
        scalar("mealCarbs", input.meal.carbs.toBits())
        scalar("mealCob", input.meal.mealCob.toBits())
        scalar("microBolusAllowed", input.microBolusAllowed)
        scalar("recentSmbVolume60Min", input.recentSmbVolume60Min.toBits())
        scalar("cumulativeSmbCap60Min", input.cumulativeSmbCap60Min.toBits())
        scalar("recentLowBg45Min", input.recentLowBg45Min.toBits())
        scalar("timeSinceLastSmbMin", input.timeSinceLastSmbMin.toBits())
        scalar("postRescueCap", input.postRescueTightRampCap?.toBits())
        scalar("modelHypoRisk", input.modelScores?.hypoRisk?.toBits())
        scalar("modelMealLikelihood", input.modelScores?.mealLikelihood?.toBits())
    }
}
