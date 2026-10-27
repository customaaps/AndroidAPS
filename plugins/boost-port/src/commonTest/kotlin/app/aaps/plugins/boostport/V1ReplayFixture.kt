package app.aaps.plugins.boostport

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.plugins.aps.openAPSBoost.BoostV1ReplayEngine
import app.aaps.plugins.aps.openAPSBoost.replay.*
import app.aaps.plugins.aps.openAPSBoost.replay.V1ReplayProfile as OapsProfileBoost

internal data class ReplayGlucose(
    override val glucose: Double,
    override val delta: Double = 0.0,
    override val shortAvgDelta: Double = 0.0,
    override val longAvgDelta: Double = 0.0,
    override val date: Long = V1ReplayFixture.now,
    override val noise: Double = 0.0
) : GlucoseStatus

internal object V1ReplayFixture {
    const val now = 1_790_000_000_000L
    val clock = object : V1ReplayClock {
        override fun hourAt(timestampMs: Long) = timestampMs.floorDiv(3_600_000L).mod(24).toInt()
        override fun minuteAt(timestampMs: Long) = timestampMs.floorDiv(60_000L).mod(60).toInt()
    }
    // Cross-platform tests compare numeric results; JVM source-parity tests
    // supply original DecimalFormat presentation separately.
    val presentation = object : V1ReplayPresentation {
        override fun glucose(value: Double) = value.toString()
        override fun fixed2(value: Double) = value.toString()
        override fun withoutZeros(value: Double) = value.toString()
    }
    fun profile(
        bg: Double,
        maxIob: Double = 10.0,
        insulinReqPercent: Double = 85.0,
        percentScale: Double = 250.0,
        recentLowBG: Double = 999.0,
        boostBolus: Double = 3.0,
        boostMaxIob: Double = 10.0,
        boostScale: Double = 1.0,
    ): OapsProfileBoost = OapsProfileBoost(
        dia = 5.0, min_5m_carbimpact = 8.0, max_iob = maxIob, max_daily_basal = 1.0, max_basal = 4.0,
        min_bg = 100.0, max_bg = 100.0, target_bg = 100.0, carb_ratio = 10.0, sens = 50.0,
        autosens_adjust_targets = false, max_daily_safety_multiplier = 3.0, current_basal_safety_multiplier = 4.0,
        high_temptarget_raises_sensitivity = false, low_temptarget_lowers_sensitivity = false,
        sensitivity_raises_target = false, resistance_lowers_target = false, adv_target_adjustments = false,
        exercise_mode = false, half_basal_exercise_target = 160, maxCOB = 120, skip_neutral_temps = false,
        remainingCarbsCap = 90, enableUAM = true, A52_risk_enable = false, SMBInterval = 3,
        enableSMB_with_COB = true, enableSMB_with_temptarget = false, allowSMB_with_high_temptarget = false,
        enableSMB_always = true, enableSMB_after_carbs = false, maxSMBBasalMinutes = 30, maxUAMSMBBasalMinutes = 30,
        bolus_increment = 0.05, carbsReqThreshold = 1, current_basal = 1.0, temptargetSet = false, autosens_max = 1.2,
        out_units = "mg/dl", lgsThreshold = null,
        variable_sens = 50.0, insulinDivisor = 75, TDD = 40.0,
        dynISFBgCap = 210.0, dynISFBgCapped = bg, sensNormalTarget = 50.0, normalTarget = 99.0, dynISFvelocity = 1.0,
        insulinPeak = 75,
        boostActive = true, profileSwitch = 100, boost_bolus = boostBolus, boost_maxIOB = boostMaxIob,
        Boost_InsulinReq = insulinReqPercent, boost_scale = boostScale, boost_percent_scale = percentScale,
        enableBoostPercentScale = true, enableCircadianISF = false, allowBoost_with_high_temptarget = false,
        recentSteps5Minutes = 0, recentSteps15Minutes = 0, recentSteps30Minutes = 0, recentSteps60Minutes = 0,
        recentLowBG = recentLowBG, recentBrakingProduct = 0.0, dynIsfMode = true,
    )

    fun iob(iob: Double, activity: Double = 0.0): Array<IobTotal> {
        val zt = IobTotal(time = now, iob = iob, activity = activity)
        return Array(48) { i ->
            IobTotal(time = now + i * 5 * 60_000L, iob = iob, activity = activity, lastBolusTime = now - 3_600_000L, iobWithZeroTemp = zt)
        }
    }


    data class Cycle(
        val glucose: ReplayGlucose,
        val profile: OapsProfileBoost,
        val iob: Array<IobTotal>,
        val temp: CurrentTemp = CurrentTemp(0, 0.0, 0),
        val meal: MealData = MealData(),
        val autosens: AutosensResult = AutosensResult(),
        val microBolusAllowed: Boolean = true,
        val flat: Boolean = false,
        val riskModel: V1ReplayRiskModel? = null,
        val mealModel: V1ReplayMealModel? = null,
        val recentSmb: Double = 0.0,
        val cumulativeCap: Double = 0.0,
        val recentLow45: Double = 999.0,
        val sinceLastSmb: Double = 720.0,
        val rescueRampCap: Double? = null,
        val time: Long = now
    )

    fun cycle(bg: Double = 130.0, delta: Double = 6.0, short: Double = 6.0,
              long: Double = 3.0, iob: Double = 0.2): Cycle = Cycle(
        ReplayGlucose(bg, delta, short, long), profile(bg), V1ReplayFixture.iob(iob)
    )

    fun run(c: Cycle, engine: BoostV1ReplayEngine = BoostV1ReplayEngine(presentation, clock)): V1ReplayResult = engine.determine_basal(
        glucose_status = c.glucose, currenttemp = c.temp.copy(),
        iob_data_array = c.iob, profile = c.profile.copy(), autosens_data = c.autosens.copy(),
        meal_data = c.meal.copy(), microBolusAllowed = c.microBolusAllowed,
        currentTime = c.time, flatBGsDetected = c.flat,
        riskModel = c.riskModel, mealModel = c.mealModel,
        recentSmbVolume60Min = c.recentSmb, cumulativeSmbCap60Min = c.cumulativeCap,
        recentLowBG45Min = c.recentLow45, timeSinceLastSmbMin = c.sinceLastSmb,
        postRescueTightRampCap = c.rescueRampCap
    )

    fun recorded(c: Cycle): V1ReplayCycle = V1ReplayCycle(
        glucose = c.glucose, currentTemp = c.temp, iob = c.iob, profile = c.profile,
        autosens = c.autosens, meal = c.meal, microBolusAllowed = c.microBolusAllowed,
        time = c.time, flatBGsDetected = c.flat, recentSmbVolume60Min = c.recentSmb,
        cumulativeSmbCap60Min = c.cumulativeCap, recentLowBG45Min = c.recentLow45,
        timeSinceLastSmbMin = c.sinceLastSmb, postRescueTightRampCap = c.rescueRampCap,
    )
}
